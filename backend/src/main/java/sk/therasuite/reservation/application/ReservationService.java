package sk.therasuite.reservation.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.reservation.application.ReservationViews.Detail;
import sk.therasuite.reservation.application.ReservationViews.GuestView;
import sk.therasuite.reservation.application.ReservationViews.ListItem;
import sk.therasuite.reservation.application.ReservationViews.PageView;
import sk.therasuite.reservation.domain.ReservationStatus;
import sk.therasuite.reservation.domain.StayPeriod;
import sk.therasuite.reservation.infrastructure.Client;
import sk.therasuite.reservation.infrastructure.ClientRepository;
import sk.therasuite.reservation.infrastructure.Reservation;
import sk.therasuite.reservation.infrastructure.ReservationGuest;
import sk.therasuite.reservation.infrastructure.ReservationGuestRepository;
import sk.therasuite.reservation.infrastructure.ReservationQueries;
import sk.therasuite.reservation.infrastructure.ReservationQueries.Result;
import sk.therasuite.reservation.infrastructure.ReservationQueries.Row;
import sk.therasuite.reservation.infrastructure.ReservationRepository;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/**
 * Rezervačné karty: vyhľadávanie, detail, založenie a úprava (oprávnenia kontroluje controller).
 */
@Service
public class ReservationService {

  static final int MAX_PAGE_SIZE = 100;

  /** Hosť pri zakladaní karty: buď existujúci klient ({@code clientId}), alebo nový klient. */
  public record GuestInput(
      Long clientId,
      String firstName,
      String lastName,
      LocalDate birthDate,
      String nationality,
      String email,
      String phone) {}

  public record CreateCommand(
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      int adults,
      int children,
      boolean exclusiveRoomUse,
      List<GuestInput> guests) {}

  /** Neuvedené (null) polia sa nemenia; {@code version} je verzia, z ktorej klient vychádzal. */
  public record UpdateCommand(
      long version,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      Boolean doNotMove,
      Boolean earlyCheckIn,
      Boolean lateCheckOut,
      Boolean exclusiveRoomUse) {}

  private final ReservationRepository reservations;
  private final ReservationGuestRepository guests;
  private final ClientRepository clients;
  private final ReservationQueries queries;

  public ReservationService(
      ReservationRepository reservations,
      ReservationGuestRepository guests,
      ClientRepository clients,
      ReservationQueries queries) {
    this.reservations = reservations;
    this.guests = guests;
    this.clients = clients;
    this.queries = queries;
  }

  @AuditedRead(operation = "RESERVATION_VIEW", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<PageView<ListItem>> search(
      String q, ReservationStatus status, LocalDate from, LocalDate to, int page, int size) {
    if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
      throw badRequest("Stránkovanie: page ≥ 0 a size 1–" + MAX_PAGE_SIZE + ".");
    }
    if (from != null && to != null && !from.isBefore(to)) {
      throw badRequest("Parameter 'from' musí byť pred 'to'.");
    }
    Result result = queries.search(q, status, from, to, page, size);
    List<ListItem> items =
        result.rows().stream()
            .map(r ->
                new ListItem(
                    r.id(),
                    r.reservationNumber(),
                    r.guestFirstName(),
                    r.guestLastName(),
                    r.guestBirthYear(),
                    r.arrivalDate(),
                    r.departureDate(),
                    r.status(),
                    r.bookedCategoryId(),
                    r.bookedCategoryName()))
            .toList();
    List<Long> clientIds =
        result.rows().stream().map(Row::guestClientId).filter(Objects::nonNull).toList();
    return new AuditedReadResult<>(new PageView<>(items, page, size, result.total()), clientIds);
  }

  @AuditedRead(operation = "RESERVATION_VIEW", scope = DataScope.CONTACT)
  @Transactional(readOnly = true)
  public AuditedReadResult<Detail> get(long id) {
    Detail detail = detail(load(id));
    return new AuditedReadResult<>(
        detail, detail.guests().stream().map(GuestView::clientId).toList());
  }

  @AuditedOperation(operation = "RESERVATION_CREATE", useCase = "UC1")
  @Transactional
  public Detail create(CreateCommand cmd) {
    validatePeriod(cmd.arrivalDate(), cmd.departureDate());
    if (cmd.adults() < 1) {
      throw badRequest("Počet dospelých musí byť aspoň 1.");
    }
    if (cmd.children() < 0) {
      throw badRequest("Počet detí nemôže byť záporný.");
    }
    if (cmd.guests() == null || cmd.guests().isEmpty()) {
      throw badRequest("Karta musí mať aspoň jedného hosťa.");
    }
    checkReferences(cmd.bookedCategoryId(), cmd.payerId());

    List<Client> resolved = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    for (GuestInput g : cmd.guests()) {
      Client client = resolveClient(g);
      if (client.getId() != null && !seen.add(client.getId())) {
        throw badRequest("Ten istý klient je na karte viackrát.");
      }
      resolved.add(client);
    }

    Reservation reservation =
        reservations.saveAndFlush(
            new Reservation(
                queries.nextReservationNumber(),
                cmd.arrivalDate(),
                cmd.departureDate(),
                cmd.bookedCategoryId(),
                cmd.payerId(),
                cmd.adults(),
                cmd.children(),
                cmd.exclusiveRoomUse()));
    for (int i = 0; i < resolved.size(); i++) {
      Client client = resolved.get(i);
      if (client.getId() == null) {
        client = clients.saveAndFlush(client);
      }
      guests.save(new ReservationGuest(reservation.getId(), client.getId(), i == 0));
    }
    guests.flush();
    return detail(reservation);
  }

  @AuditedOperation(operation = "RESERVATION_UPDATE", useCase = "UC1")
  @Transactional
  public Detail update(long id, UpdateCommand cmd) {
    Reservation r = load(id);
    if (r.getVersion() != cmd.version()) {
      throw conflict();
    }
    LocalDate arrival = cmd.arrivalDate() != null ? cmd.arrivalDate() : r.getArrivalDate();
    LocalDate departure = cmd.departureDate() != null ? cmd.departureDate() : r.getDepartureDate();
    validatePeriod(arrival, departure);
    checkReferences(
        cmd.bookedCategoryId() != null ? cmd.bookedCategoryId() : r.getBookedCategoryId(),
        cmd.payerId() != null ? cmd.payerId() : r.getPayerId());

    r.setArrivalDate(arrival);
    r.setDepartureDate(departure);
    if (cmd.bookedCategoryId() != null) {
      r.setBookedCategoryId(cmd.bookedCategoryId());
    }
    if (cmd.payerId() != null) {
      r.setPayerId(cmd.payerId());
    }
    if (cmd.doNotMove() != null) {
      r.setDoNotMove(cmd.doNotMove());
    }
    if (cmd.earlyCheckIn() != null) {
      r.setEarlyCheckIn(cmd.earlyCheckIn());
    }
    if (cmd.lateCheckOut() != null) {
      r.setLateCheckOut(cmd.lateCheckOut());
    }
    if (cmd.exclusiveRoomUse() != null) {
      r.setExclusiveRoomUse(cmd.exclusiveRoomUse());
    }
    return detail(reservations.saveAndFlush(r));
  }

  private Client resolveClient(GuestInput g) {
    if (g.clientId() != null) {
      return clients
          .findById(g.clientId())
          .orElseThrow(() -> badRequest("Klient " + g.clientId() + " neexistuje."));
    }
    if (isBlank(g.firstName()) || isBlank(g.lastName())) {
      throw badRequest("Nový klient musí mať meno a priezvisko (alebo uveďte clientId).");
    }
    return new Client(
        g.firstName().trim(),
        g.lastName().trim(),
        g.birthDate(),
        g.nationality(),
        g.email(),
        g.phone());
  }

  private void checkReferences(Long categoryId, Long payerId) {
    if (categoryId == null || !queries.categoryExists(categoryId)) {
      throw badRequest("Kategória izby neexistuje.");
    }
    if (payerId == null || !queries.payerExists(payerId)) {
      throw badRequest("Platiteľ neexistuje.");
    }
  }

  private static void validatePeriod(LocalDate arrival, LocalDate departure) {
    String violation = StayPeriod.violation(arrival, departure);
    if (violation != null) {
      throw badRequest(violation);
    }
  }

  private Reservation load(long id) {
    return reservations
        .findById(id)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Karta neexistuje."));
  }

  private Detail detail(Reservation r) {
    List<GuestView> views =
        guests.findByReservationIdOrderById(r.getId()).stream()
            .map(g -> {
              Client c = clients.getReferenceById(g.getClientId());
              return new GuestView(
                  g.getId(),
                  c.getId(),
                  c.getFirstName(),
                  c.getLastName(),
                  c.getBirthDate(),
                  c.getNationality(),
                  c.getEmail(),
                  c.getPhone(),
                  g.isPrimaryGuest(),
                  g.isCancelled());
            })
            .toList();
    return new Detail(
        r.getId(),
        r.getReservationNumber(),
        r.getStatus(),
        r.getArrivalDate(),
        r.getDepartureDate(),
        r.getBookedCategoryId(),
        r.getPayerId(),
        r.getGroupId(),
        r.getPartyId(),
        r.isExclusiveRoomUse(),
        r.getPriceCategoryMode(),
        r.getPriceType(),
        r.isDoNotMove(),
        r.isEarlyCheckIn(),
        r.isLateCheckOut(),
        r.getAdults(),
        r.getChildren(),
        r.getVersion(),
        views);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  private static ApiException conflict() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "Záznam medzitým zmenil iný používateľ. Načítajte ho znova.");
  }
}
