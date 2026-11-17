package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.StayViews.StayView;
import sk.therasuite.accommodation.domain.OccupancyCode;
import sk.therasuite.accommodation.domain.OccupancyGuard;
import sk.therasuite.accommodation.domain.OccupancyModel.Context;
import sk.therasuite.accommodation.domain.OccupancyModel.Reservation;
import sk.therasuite.accommodation.domain.OccupancyModel.StayChange;
import sk.therasuite.accommodation.domain.OccupancyModel.UserRights;
import sk.therasuite.accommodation.domain.OccupancyResult;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.BedRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.CardRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.RoomRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.StayRef;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.StayRow;
import sk.therasuite.accommodation.infrastructure.Stay;
import sk.therasuite.accommodation.infrastructure.StayRepository;
import sk.therasuite.audit.application.AuditService;
import sk.therasuite.businessday.application.BusinessDateProvider;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.notification.application.LiveEvent;
import sk.therasuite.reservation.application.ReservationService;
import sk.therasuite.reservation.application.ReservationService.UpdateCommand;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/** Pobyty rezervačnej karty: zobrazenie, ubytovanie (UC1, UC2, UC4) a zrušenie budúceho pobytu. */
@Service
public class StayService {

  /** Telo požiadavky na ubytovanie; {@code confirm} sú kódy potvrdených upozornení. */
  public record CreateCommand(
      long bedId,
      LocalDate from,
      LocalDate to,
      boolean adjustReservationDates,
      List<String> confirm) {}

  private static final String EXCLUSION_VIOLATION = "23P01";
  private static final Set<String> ACTIVE_STATUSES = Set.of("RESERVED", "CHECKED_IN");

  private final StayRepository stays;
  private final AccommodationQueries queries;
  private final PermissionChecker permissions;
  private final BusinessDateProvider businessDates;
  private final ReservationService reservations;
  private final ApplicationEventPublisher events;
  private final OccupancyGuard guard = new OccupancyGuard();

  public StayService(
      StayRepository stays,
      AccommodationQueries queries,
      PermissionChecker permissions,
      BusinessDateProvider businessDates,
      ReservationService reservations,
      ApplicationEventPublisher events) {
    this.stays = stays;
    this.queries = queries;
    this.permissions = permissions;
    this.businessDates = businessDates;
    this.reservations = reservations;
    this.events = events;
  }

  /** „Zobraziť ubytovanie“ karty; audit čítania so skráteným rozsahom {@code NAME}. */
  @AuditedRead(operation = "STAY_VIEW", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<List<StayView>> listForReservation(long reservationId) {
    loadCard(reservationId);
    List<StayView> views =
        queries.staysOfReservation(reservationId).stream().map(this::view).toList();
    return new AuditedReadResult<>(views, queries.guestClientIds(reservationId));
  }

  @AuditedOperation(operation = "STAY_CREATE", useCase = "UC1")
  @Transactional
  public StayView create(long reservationId, CreateCommand cmd) {
    if (cmd.from() == null || cmd.to() == null || !cmd.from().isBefore(cmd.to())) {
      throw badRequest("Začiatok pobytu musí byť pred jeho koncom.");
    }
    StayPeriod period = new StayPeriod(cmd.from(), cmd.to());
    List<String> confirm = cmd.confirm() == null ? List.of() : cmd.confirm();
    CardRow card = loadCard(reservationId);
    if (!ACTIVE_STATUSES.contains(card.status())) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "RESERVATION_NOT_ACTIVE",
          "Kartu v stave " + card.status() + " nemožno ubytovať.");
    }
    BedRow bed =
        queries
            .bed(cmd.bedId())
            .orElseThrow(() -> badRequest("Lôžko " + cmd.bedId() + " neexistuje."));
    RoomRow room = queries.room(bed.roomId()).orElseThrow();

    permissions.require(Permissions.STAY_CREATE, bed.receptionId());
    UserRights rights = rights(bed.receptionId());

    LocalDate arrival = card.arrivalDate();
    LocalDate departure = card.departureDate();
    boolean outside = period.from().isBefore(arrival) || period.to().isAfter(departure);
    if (outside) {
      if (!cmd.adjustReservationDates()) {
        throw new ApiException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            OccupancyCode.OUTSIDE_RESERVATION.name(),
            "Pobyt presahuje termín rezervačnej karty.");
      }
      arrival = min(arrival, period.from());
      departure = max(departure, period.to());
    }

    // Prvá kontrola: chyby a upozornenia, ktoré treba potvrdiť.
    OccupancyResult first = check(card, arrival, departure, room, bed, period, rights);
    OccupancyProblems.enforce(first, confirm);

    // Zápisy izby idú za sebou; kontrola sa zopakuje tesne pred zápisom už pod zámkom izby.
    queries.lockRoom(room.id());
    OccupancyResult second = check(card, arrival, departure, room, bed, period, rights);
    OccupancyProblems.enforce(second, confirm);

    if (outside) {
      reservations.update(
          reservationId,
          new UpdateCommand(
              card.version(), arrival, departure, null, null, null, null, null, null));
    }
    Stay saved;
    try {
      saved =
          stays.saveAndFlush(
              new Stay(reservationId, bed.bedId(), period.from(), period.to(), room.categoryId()));
    } catch (DataIntegrityViolationException e) {
      if (isExclusionViolation(e)) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            OccupancyCode.BED_OCCUPIED.name(),
            "Lôžko je v intervale obsadené.");
      }
      throw e;
    }
    events.publishEvent(
        new LiveEvent.PlanChanged(
            bed.receptionId(),
            List.of(room.id()),
            period.from(),
            period.to(),
            AuditService.currentEventId()));
    return view(queries.stay(saved.getId()).orElseThrow());
  }

  /** Zruší budúci pobyt: začiatok nesmie byť pred obchodným dňom recepcie izby. */
  @AuditedOperation(operation = "STAY_DELETE")
  @Transactional
  public void delete(long stayId) {
    StayRef ref =
        queries
            .stayRef(stayId)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Pobyt neexistuje."));
    permissions.require(Permissions.STAY_CREATE, ref.receptionId());
    if (ref.dateFrom().isBefore(businessDates.currentForRoom(ref.roomId()))) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          OccupancyCode.PAST_CHANGE.name(),
          "Zrušiť možno len pobyt, ktorý začína v obchodný deň alebo neskôr.");
    }
    stays.deleteById(stayId);
    stays.flush();
    events.publishEvent(
        new LiveEvent.PlanChanged(
            ref.receptionId(),
            List.of(ref.roomId()),
            ref.dateFrom(),
            ref.dateTo(),
            AuditService.currentEventId()));
  }

  private OccupancyResult check(
      CardRow card,
      LocalDate arrival,
      LocalDate departure,
      RoomRow room,
      BedRow bed,
      StayPeriod period,
      UserRights rights) {
    LocalDate day = businessDates.currentForRoom(room.id());
    List<Integer> ages =
        queries.guestBirthDates(card.id()).stream()
            .map(birth -> Period.between(birth, period.from()).getYears())
            .toList();
    Context ctx =
        new Context(
            List.of(room.toModel(day)),
            queries.bedsOfRoom(room.id()),
            queries.staysInRoom(room.id(), period),
            queries.blocksInRoom(room.id(), period),
            List.of(
                new Reservation(
                    card.id(),
                    card.bookedCategoryId(),
                    arrival,
                    departure,
                    card.exclusiveRoomUse(),
                    card.doNotMove(),
                    ages)));
    return guard.check(ctx, List.of(StayChange.create(card.id(), bed.bedId(), period)), rights);
  }

  private UserRights rights(long receptionId) {
    return new UserRights(
        permissions.has(Permissions.STAY_BACKDATE, receptionId),
        permissions.has(Permissions.STAY_MOVE_LOCKED, receptionId),
        permissions.has(Permissions.STAY_OTHER_CATEGORY, receptionId),
        permissions.limit(Permissions.STAY_CREATE, receptionId));
  }

  private CardRow loadCard(long id) {
    return queries
        .card(id)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Karta neexistuje."));
  }

  private StayView view(StayRow r) {
    return new StayView(
        r.id(),
        r.reservationId(),
        r.version(),
        r.buildingId(),
        r.buildingName(),
        r.roomId(),
        r.roomNumber(),
        r.bedId(),
        r.bedNumber(),
        r.bedType(),
        r.dateFrom(),
        r.dateTo(),
        r.priceCategoryId(),
        r.priceCategoryName(),
        r.locked());
  }

  static boolean isExclusionViolation(Throwable e) {
    for (Throwable c = e; c != null; c = c.getCause()) {
      if (c instanceof java.sql.SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
        return true;
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return false;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }

  private static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }
}
