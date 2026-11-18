package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.PlanViews.Bar;
import sk.therasuite.accommodation.application.PlanViews.BedView;
import sk.therasuite.accommodation.application.PlanViews.CategoryRef;
import sk.therasuite.accommodation.application.PlanViews.HeldBedBar;
import sk.therasuite.accommodation.application.PlanViews.Plan;
import sk.therasuite.accommodation.application.PlanViews.PlanRoom;
import sk.therasuite.accommodation.application.PlanViews.ReceptionDay;
import sk.therasuite.accommodation.application.PlanViews.RoomBlockBar;
import sk.therasuite.accommodation.application.PlanViews.StayBar;
import sk.therasuite.accommodation.domain.HeldBedCalculator;
import sk.therasuite.accommodation.domain.HeldBedCalculator.HeldBed;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanBlockRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanData;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanRoomRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.PlanStayRow;
import sk.therasuite.catalog.domain.BedType;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.api.ConditionalGet;
import sk.therasuite.shared.application.ApiException;

/**
 * Plán ubytovania (HLD kap. 6.2, UC15, UC16, UC18): izby s lôžkami a pásiky pobytov, držaných lôžok
 * a blokácií. Dáta sa načítajú štyrmi dotazmi a poskladajú v pamäti.
 */
@Service
public class AccommodationPlanService {

  /** Najdlhšie obdobie plánu v nociach. */
  static final long MAX_NIGHTS = 366;

  /** Filtre plánu; {@code null} alebo prázdny zoznam = bez obmedzenia. */
  public record PlanFilter(
      List<Long> buildingIds,
      List<Long> roomIds,
      Long receptionId,
      boolean freeOnly,
      boolean fullyFree) {}

  private final AccommodationQueries queries;
  private final PermissionChecker permissions;
  private final HeldBedCalculator heldBeds = new HeldBedCalculator();

  public AccommodationPlanService(AccommodationQueries queries, PermissionChecker permissions) {
    this.queries = queries;
    this.permissions = permissions;
  }

  /**
   * Vracia len izby budov, ku ktorým má používateľ oprávnenie PLAN_VIEW (rozsah na recepciu a
   * stredisko). Audit čítania so rozsahom {@code NAME} a ID klientov na všetkých pásikoch.
   */
  @AuditedRead(operation = "PLAN_VIEW", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<Plan> plan(LocalDate from, LocalDate to, PlanFilter filter) {
    validateRequest(from, to, filter);
    StayPeriod period = new StayPeriod(from, to);
    PlanData data =
        queries.loadPlan(
            period,
            filter.buildingIds(),
            filter.roomIds(),
            filter.receptionId(),
            reception -> permissions.has(Permissions.PLAN_VIEW, reception));
    return assemble(period, data, filter);
  }

  /**
   * Odtlačok plánu pre ETag: počítadlo zmien recepcie, rozsah dátumov, filtre a ID používateľa.
   * Kontroluje parametre aj oprávnenie ako {@link #plan}, ale nič nečíta ani neauditúje.
   */
  @Transactional(readOnly = true)
  public String planEtag(long userId, LocalDate from, LocalDate to, PlanFilter filter) {
    validateRequest(from, to, filter);
    return ConditionalGet.etag(
        "plan",
        userId,
        from,
        to,
        filter.buildingIds(),
        filter.roomIds(),
        filter.receptionId(),
        filter.freeOnly(),
        filter.fullyFree(),
        queries.changeSeqToken(filter.receptionId()));
  }

  private void validateRequest(LocalDate from, LocalDate to, PlanFilter filter) {
    if (from == null
        || to == null
        || !from.isBefore(to)
        || ChronoUnit.DAYS.between(from, to) > MAX_NIGHTS) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          "Parameter 'from' musí byť pred 'to' a obdobie nesmie presiahnuť "
              + MAX_NIGHTS
              + " nocí.");
    }
    if (filter.receptionId() != null) {
      permissions.require(Permissions.PLAN_VIEW, filter.receptionId());
    } else {
      permissions.require(Permissions.PLAN_VIEW);
    }
  }

  /**
   * Pásiky vybraných izieb v období, pre odpoveď zápisových operácií. Bez auditu čítania a bez
   * kontroly PLAN_VIEW: volajúca zápisová operácia je už auditovaná a oprávnená.
   */
  public Plan roomsOf(List<Long> roomIds, StayPeriod period) {
    PlanData data = queries.loadPlan(period, null, roomIds, null, reception -> true);
    return assemble(period, data, new PlanFilter(null, roomIds, null, false, false)).value();
  }

  private AuditedReadResult<Plan> assemble(StayPeriod period, PlanData data, PlanFilter filter) {
    Map<Long, List<PlanStayRow>> staysByRoom = new LinkedHashMap<>();
    data.stays()
        .forEach(s -> staysByRoom.computeIfAbsent(s.roomId(), k -> new ArrayList<>()).add(s));
    Map<Long, List<PlanBlockRow>> blocksByRoom = new LinkedHashMap<>();
    data.blocks()
        .forEach(b -> blocksByRoom.computeIfAbsent(b.roomId(), k -> new ArrayList<>()).add(b));

    Map<Long, ReceptionDay> receptions = new LinkedHashMap<>();
    List<PlanRoom> rooms = new ArrayList<>();
    Set<Long> clientIds = new LinkedHashSet<>();
    for (PlanRoomRow room : data.rooms()) {
      receptions.putIfAbsent(
          room.receptionId(), new ReceptionDay(room.receptionId(), room.businessDate()));
      List<PlanStayRow> stays = staysByRoom.getOrDefault(room.roomId(), List.of());
      List<PlanBlockRow> blocks = blocksByRoom.getOrDefault(room.roomId(), List.of());
      List<HeldBed> held = heldBedsOf(room, stays, data.exclusiveReservationIds(), period);
      if (!matchesFilter(room, stays, blocks, held, filter)) {
        continue;
      }
      List<Bar> bars = new ArrayList<>();
      for (PlanStayRow s : stays) {
        bars.add(stayBar(s, period));
        if (s.clientId() != null) {
          clientIds.add(s.clientId());
        }
      }
      for (HeldBed h : held) {
        bars.add(heldBar(h, stays));
      }
      for (PlanBlockRow b : blocks) {
        bars.add(blockBar(b, period));
      }
      rooms.add(
          new PlanRoom(
              room.roomId(),
              room.buildingId(),
              room.buildingCode(),
              room.number(),
              room.floor(),
              new CategoryRef(room.categoryId(), room.categoryCode()),
              room.beds().stream().map(b -> new BedView(b.id(), b.bedNumber(), b.type())).toList(),
              bars));
    }
    Plan plan = new Plan(period.from(), period.to(), List.copyOf(receptions.values()), rooms);
    return new AuditedReadResult<>(plan, clientIds);
  }

  private List<HeldBed> heldBedsOf(
      PlanRoomRow room, List<PlanStayRow> stays, Set<Long> exclusive, StayPeriod period) {
    if (stays.stream().noneMatch(s -> exclusive.contains(s.reservationId()))) {
      return List.of();
    }
    List<Bed> beds =
        room.beds().stream()
            .map(b -> new Bed(b.id(), room.roomId(), BedType.valueOf(b.type()), true))
            .toList();
    List<ExistingStay> existing =
        stays.stream()
            .map(s ->
                new ExistingStay(
                    s.id(),
                    s.reservationId(),
                    s.bedId(),
                    new StayPeriod(s.dateFrom(), s.dateTo()),
                    s.locked(),
                    exclusive.contains(s.reservationId())))
            .toList();
    return heldBeds.calculate(beds, existing, period);
  }

  /**
   * {@code freeOnly}: izba bez blokácie s aspoň jedným riadnym lôžkom bez pobytu a bez držania v
   * celom období. {@code fullyFree}: izba bez blokácie a bez akéhokoľvek pobytu v období.
   */
  private static boolean matchesFilter(
      PlanRoomRow room,
      List<PlanStayRow> stays,
      List<PlanBlockRow> blocks,
      List<HeldBed> held,
      PlanFilter filter) {
    if (filter.fullyFree() && (!stays.isEmpty() || !blocks.isEmpty())) {
      return false;
    }
    if (filter.freeOnly()) {
      if (!blocks.isEmpty()) {
        return false;
      }
      Set<Long> taken = stays.stream().map(PlanStayRow::bedId).collect(Collectors.toSet());
      held.forEach(h -> taken.add(h.bedId()));
      return room.beds().stream()
          .anyMatch(b -> b.type().equals(BedType.REGULAR.name()) && !taken.contains(b.id()));
    }
    return true;
  }

  private static StayBar stayBar(PlanStayRow s, StayPeriod period) {
    StayPeriod actual = new StayPeriod(s.dateFrom(), s.dateTo());
    StayPeriod clipped = actual.intersection(period).orElse(actual);
    List<String> flags = new ArrayList<>();
    if (s.earlyCheckIn() && s.dateFrom().equals(s.arrivalDate())) {
      flags.add("EARLY_CHECK_IN");
    }
    if (s.lateCheckOut() && s.dateTo().equals(s.departureDate())) {
      flags.add("LATE_CHECK_OUT");
    }
    return new StayBar(
        "STAY",
        s.id(),
        s.reservationId(),
        s.bedId(),
        clipped.from(),
        clipped.to(),
        s.dateFrom(),
        s.dateTo(),
        label(s.lastName(), s.firstName()),
        s.status(),
        s.locked(),
        flags,
        s.partyId());
  }

  private static HeldBedBar heldBar(HeldBed h, List<PlanStayRow> stays) {
    Long byStay =
        stays.stream()
            .filter(s -> s.reservationId() == h.reservationId())
            .filter(s -> new StayPeriod(s.dateFrom(), s.dateTo()).overlaps(h.period()))
            .min(Comparator.comparing(PlanStayRow::dateFrom))
            .map(PlanStayRow::id)
            .orElse(null);
    return new HeldBedBar(
        "HELD_BED", byStay, h.reservationId(), h.bedId(), h.period().from(), h.period().to());
  }

  private static RoomBlockBar blockBar(PlanBlockRow b, StayPeriod period) {
    StayPeriod actual = new StayPeriod(b.dateFrom(), b.dateTo());
    StayPeriod clipped = actual.intersection(period).orElse(actual);
    return new RoomBlockBar(
        "ROOM_BLOCK",
        b.id(),
        b.reason(),
        b.note(),
        clipped.from(),
        clipped.to(),
        b.dateFrom(),
        b.dateTo());
  }

  /** Skrátené meno „Priezvisko M.“. */
  static String label(String lastName, String firstName) {
    String last = lastName == null ? "" : lastName.trim();
    String first = firstName == null ? "" : firstName.trim();
    if (first.isEmpty()) {
      return last;
    }
    return last + " " + first.substring(0, first.offsetByCodePoints(0, 1)).toUpperCase() + ".";
  }
}
