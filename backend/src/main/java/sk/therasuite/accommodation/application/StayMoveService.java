package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.PlanViews.Plan;
import sk.therasuite.accommodation.domain.OccupancyCode;
import sk.therasuite.accommodation.domain.OccupancyGuard;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.Context;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.accommodation.domain.OccupancyModel.Reservation;
import sk.therasuite.accommodation.domain.OccupancyModel.Room;
import sk.therasuite.accommodation.domain.OccupancyModel.RoomBlock;
import sk.therasuite.accommodation.domain.OccupancyModel.StayChange;
import sk.therasuite.accommodation.domain.OccupancyModel.UserRights;
import sk.therasuite.accommodation.domain.OccupancyResult;
import sk.therasuite.accommodation.domain.OccupancyResult.Issue;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.BedRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.CardMeta;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.CardRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.RoomRow;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.StayDetail;
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
import sk.therasuite.shared.application.ApiException;

/**
 * Zmeny existujúcich pobytov (HLD kap. 5.3, 5.4): sťahovanie celého pobytu alebo jeho časti (UC8,
 * UC9), zmena dĺžky (UC10), dávka presunov z odkladiska a výmena dvoch klientov (UC11). Všetky
 * operácie overia <b>konečný stav</b> zmien cez {@link OccupancyGuard} a zapíšu ho v jednej
 * transakcii s jedným audit záznamom.
 */
@Service
public class StayMoveService {

  /** Požiadavka na presun jedného pobytu; {@code version} je nepovinná kontrola pobytu. */
  public record MoveItem(long stayId, long targetBedId, LocalDate fromDate, Long version) {}

  /** Zmena konca pobytu a/alebo zámku; aspoň jedna z hodnôt {@code to}, {@code locked}. */
  public record ResizeCommand(long version, LocalDate to, Boolean locked, List<String> confirm) {}

  private enum Kind {
    MOVE,
    SPLIT,
    RESIZE
  }

  /** Jedna zmena pobytu: nové lôžko a interval, deň začiatku zmeny pre kontrolu „nie spätne“. */
  private record Op(
      Kind kind, StayDetail stay, BedRow target, StayPeriod period, LocalDate effectiveFrom) {}

  /** Výsledok kontroly: karty, ktorým sa zruší výhradné užívanie, a údaje izieb. */
  private record Verified(Set<Long> releaseCards, Map<Long, RoomRow> rooms) {}

  private static final Set<String> ACTIVE_STATUSES = Set.of("RESERVED", "CHECKED_IN");

  private final StayRepository stays;
  private final AccommodationQueries queries;
  private final PermissionChecker permissions;
  private final BusinessDateProvider businessDates;
  private final ReservationService reservations;
  private final AccommodationPlanService plans;
  private final ApplicationEventPublisher events;
  private final OccupancyGuard guard = new OccupancyGuard();

  public StayMoveService(
      StayRepository stays,
      AccommodationQueries queries,
      PermissionChecker permissions,
      BusinessDateProvider businessDates,
      ReservationService reservations,
      AccommodationPlanService plans,
      ApplicationEventPublisher events) {
    this.stays = stays;
    this.queries = queries;
    this.permissions = permissions;
    this.businessDates = businessDates;
    this.reservations = reservations;
    this.plans = plans;
    this.events = events;
  }

  /** Presun celého pobytu (UC8) alebo od dňa {@code fromDate} (UC9). */
  @AuditedOperation(operation = "STAY_MOVE", useCase = "UC8")
  @Transactional
  public Plan move(long stayId, MoveItem item, List<String> confirm) {
    return applyMoves(
        List.of(new MoveItem(stayId, item.targetBedId(), item.fromDate(), item.version())),
        confirm);
  }

  /** Dávka presunov z odkladiska: overí sa konečný stav, zapíše sa všetko alebo nič. */
  @AuditedOperation(operation = "STAY_MOVE_BATCH", useCase = "UC8")
  @Transactional
  public Plan moveBatch(List<MoveItem> items, List<String> confirm) {
    return applyMoves(items, confirm);
  }

  /** Výmena lôžok dvoch pobytov (UC11). */
  @AuditedOperation(operation = "STAY_SWAP", useCase = "UC11")
  @Transactional
  public Plan swap(long stayIdA, long stayIdB, List<String> confirm) {
    if (stayIdA == stayIdB) {
      throw badRequest("Výmena vyžaduje dva rôzne pobyty.");
    }
    StayDetail a = detail(stayIdA);
    StayDetail b = detail(stayIdB);
    if (a.bedId() == b.bedId()) {
      throw badRequest("Pobyty sú na rovnakom lôžku.");
    }
    return applyMoves(
        List.of(
            new MoveItem(a.id(), b.bedId(), null, a.version()),
            new MoveItem(b.id(), a.bedId(), null, b.version())),
        confirm);
  }

  /** Zmena konca pobytu (UC10) a zámku „nepresúvať“ (UC19). */
  @AuditedOperation(operation = "STAY_RESIZE", useCase = "UC10")
  @Transactional
  public Plan resize(long stayId, ResizeCommand cmd) {
    if (cmd.to() == null && cmd.locked() == null) {
      throw badRequest("Zadajte nový koniec pobytu alebo zámok.");
    }
    List<String> confirm = cmd.confirm() == null ? List.of() : cmd.confirm();
    StayDetail first = detail(stayId);
    permissions.require(Permissions.STAY_MOVE, first.receptionId());
    queries.lockRoom(first.roomId());
    StayDetail stay = detail(stayId);
    if (stay.roomId() != first.roomId() || stay.version() != cmd.version()) {
      throw stale();
    }
    List<Op> ops = new ArrayList<>();
    Map<Long, CardRow> cards = new HashMap<>();
    CardRow card = activeCard(stay.reservationId());
    cards.put(card.id(), card);
    LocalDate newTo = cmd.to();
    if (newTo != null && !newTo.equals(stay.dateTo())) {
      if (!stay.dateFrom().isBefore(newTo)) {
        throw badRequest("Koniec pobytu musí byť po jeho začiatku.");
      }
      BedRow bed = queries.bed(stay.bedId()).orElseThrow();
      LocalDate changed = newTo.isBefore(stay.dateTo()) ? newTo : stay.dateTo();
      ops.add(new Op(Kind.RESIZE, stay, bed, new StayPeriod(stay.dateFrom(), newTo), changed));
      if (newTo.isAfter(card.departureDate())) {
        // Predĺženie posunie odchod karty; skrátenie ho nemení.
        cards.put(
            card.id(),
            new CardRow(
                card.id(),
                card.status(),
                card.arrivalDate(),
                newTo,
                card.bookedCategoryId(),
                card.exclusiveRoomUse(),
                card.doNotMove(),
                card.version()));
      }
    }
    if (!ops.isEmpty()) {
      verify(ops, cards, confirm);
    }
    if (!ops.isEmpty() && newTo.isAfter(card.departureDate())) {
      reservations.update(
          card.id(),
          new UpdateCommand(card.version(), null, newTo, null, null, null, null, null, null));
    }
    Stay entity = stays.findById(stay.id()).orElseThrow();
    if (!ops.isEmpty()) {
      entity.setDateTo(newTo);
    }
    if (cmd.locked() != null) {
      entity.setLocked(cmd.locked());
    }
    flush(() -> stays.saveAndFlush(entity));
    if (ops.isEmpty()) {
      ops.add(
          new Op(
              Kind.RESIZE,
              stay,
              null,
              new StayPeriod(stay.dateFrom(), stay.dateTo()),
              stay.dateFrom()));
    }
    publishChanges(ops);
    return plan(ops);
  }

  private Plan applyMoves(List<MoveItem> items, List<String> confirm) {
    if (items == null || items.isEmpty()) {
      throw badRequest("Zoznam presunov je prázdny.");
    }
    if (items.stream().map(MoveItem::stayId).distinct().count() != items.size()) {
      throw badRequest("Pobyt sa v dávke smie vyskytnúť najviac raz.");
    }
    List<String> confirmed = confirm == null ? List.of() : confirm;

    // Izby sa zamykajú vzostupne podľa id, aby sa dve dávky navzájom nezablokovali.
    Set<Long> roomIds = new TreeSet<>();
    for (MoveItem item : items) {
      StayDetail s = detail(item.stayId());
      permissions.require(Permissions.STAY_MOVE, s.receptionId());
      BedRow target = targetBed(item);
      permissions.require(Permissions.STAY_MOVE, target.receptionId());
      roomIds.add(s.roomId());
      roomIds.add(target.roomId());
    }
    roomIds.forEach(queries::lockRoom);

    List<Op> ops = new ArrayList<>();
    Map<Long, CardRow> cards = new LinkedHashMap<>();
    for (MoveItem item : items) {
      StayDetail s = detail(item.stayId());
      if (!roomIds.contains(s.roomId())
          || (item.version() != null && item.version() != s.version())) {
        throw stale();
      }
      BedRow target = targetBed(item);
      if (target.bedId() == s.bedId()) {
        throw badRequest("Pobyt " + s.id() + " už je na tomto lôžku.");
      }
      cards.computeIfAbsent(s.reservationId(), this::activeCard);
      if (item.fromDate() == null) {
        ops.add(
            new Op(Kind.MOVE, s, target, new StayPeriod(s.dateFrom(), s.dateTo()), s.dateFrom()));
      } else {
        LocalDate d = item.fromDate();
        if (!d.isAfter(s.dateFrom()) || !d.isBefore(s.dateTo())) {
          throw badRequest(
              "Deň presunu musí byť vnútri pobytu " + s.dateFrom() + " – " + s.dateTo() + ".");
        }
        ops.add(new Op(Kind.SPLIT, s, target, new StayPeriod(d, s.dateTo()), d));
      }
    }
    Verified verified = verify(ops, cards, confirmed);

    queries.deferStayOverlap();
    for (long cardId : verified.releaseCards()) {
      // Presun na držané lôžko: karta stráca výhradné užívanie izby (v tom istom audite).
      reservations.update(
          cardId,
          new UpdateCommand(
              queries.card(cardId).orElseThrow().version(),
              null,
              null,
              null,
              null,
              null,
              null,
              null,
              false));
    }
    List<Stay> toSave = new ArrayList<>();
    for (Op op : ops) {
      Stay stay = stays.findById(op.stay().id()).orElseThrow();
      CardRow card = cards.get(op.stay().reservationId());
      long price = priceCategory(card, verified.rooms().get(op.target().roomId()));
      if (op.kind() == Kind.SPLIT) {
        stay.setDateTo(op.period().from());
        Stay moved =
            new Stay(card.id(), op.target().bedId(), op.period().from(), op.period().to(), price);
        moved.setLocked(op.stay().locked());
        toSave.add(moved);
      } else {
        stay.setBedId(op.target().bedId());
        stay.setPriceCategoryId(price);
      }
      toSave.add(stay);
    }
    flush(() -> {
      stays.saveAllAndFlush(toSave);
      queries.enforceStayOverlap();
    });
    publishChanges(ops);
    return plan(ops);
  }

  /** Overí konečný stav zmien; vyhodí chybu, alebo 409 s upozorneniami na potvrdenie. */
  private Verified verify(List<Op> ops, Map<Long, CardRow> cards, List<String> confirm) {
    Set<Long> roomIds = new TreeSet<>();
    for (Op op : ops) {
      roomIds.add(op.stay().roomId());
      roomIds.add(op.target().roomId());
    }
    // Obchodný deň cieľovej izby; pri presune medzi recepciami platí neskorší z dní.
    Map<Long, LocalDate> days = new HashMap<>();
    for (long roomId : roomIds) {
      days.put(roomId, businessDates.currentForRoom(roomId));
    }
    for (Op op : ops) {
      LocalDate latest = businessDates.latestOf(List.of(op.stay().roomId(), op.target().roomId()));
      days.merge(op.target().roomId(), latest, StayMoveService::max);
    }
    LocalDate from = null;
    LocalDate to = null;
    for (Op op : ops) {
      from = min(from, min(op.stay().dateFrom(), op.period().from()));
      to = max(to, max(op.stay().dateTo(), op.period().to()));
    }
    StayPeriod hull = new StayPeriod(from, to);

    Map<Long, RoomRow> roomRows = new HashMap<>();
    List<Room> rooms = new ArrayList<>();
    List<Bed> beds = new ArrayList<>();
    List<ExistingStay> existing = new ArrayList<>();
    List<RoomBlock> blocks = new ArrayList<>();
    for (long roomId : roomIds) {
      RoomRow row = queries.room(roomId).orElseThrow();
      roomRows.put(roomId, row);
      rooms.add(row.toModel(days.get(roomId)));
      beds.addAll(queries.bedsOfRoom(roomId));
      existing.addAll(queries.staysInRoom(roomId, hull));
      blocks.addAll(queries.blocksInRoom(roomId, hull));
    }
    // Zostatok rozdeleného pobytu ostáva na pôvodnom lôžku; do konečného stavu patrí tiež.
    for (Op op : ops) {
      if (op.kind() == Kind.SPLIT) {
        existing.add(
            new ExistingStay(
                -op.stay().id(),
                op.stay().reservationId(),
                op.stay().bedId(),
                new StayPeriod(op.stay().dateFrom(), op.period().from()),
                op.stay().locked(),
                cards.get(op.stay().reservationId()).exclusiveRoomUse()));
      }
    }
    List<Reservation> models = new ArrayList<>();
    for (CardRow card : cards.values()) {
      LocalDate start =
          ops.stream()
              .filter(o -> o.stay().reservationId() == card.id())
              .map(o -> o.period().from())
              .min(LocalDate::compareTo)
              .orElseThrow();
      List<Integer> ages =
          queries.guestBirthDates(card.id()).stream()
              .map(birth -> Period.between(birth, start).getYears())
              .toList();
      models.add(
          new Reservation(
              card.id(),
              card.bookedCategoryId(),
              card.arrivalDate(),
              card.departureDate(),
              card.exclusiveRoomUse(),
              card.doNotMove(),
              ages));
    }
    List<StayChange> changes =
        ops.stream()
            .map(o ->
                StayChange.update(
                    o.stay().id(),
                    o.stay().reservationId(),
                    o.target().bedId(),
                    o.period(),
                    o.effectiveFrom()))
            .toList();
    OccupancyResult guarded =
        guard.check(new Context(rooms, beds, existing, blocks, models), changes, rights(ops));

    List<Issue> warnings = new ArrayList<>(guarded.warnings());
    warnings.addAll(partyWarnings(ops));
    OccupancyResult result = new OccupancyResult(guarded.errors(), warnings);
    OccupancyProblems.enforce(result, confirm);

    Set<Long> release = new HashSet<>();
    if (result.warnings().stream().anyMatch(w -> w.code() == OccupancyCode.RELEASES_HELD_BED)) {
      release = heldCards(ops, existing, cards, beds);
    }
    return new Verified(release, roomRows);
  }

  /** Karty s výhradným užívaním, ktorých držané lôžko presun obsadzuje. */
  private static Set<Long> heldCards(
      List<Op> ops, List<ExistingStay> existing, Map<Long, CardRow> cards, List<Bed> beds) {
    Set<Long> moved = new HashSet<>();
    ops.forEach(o -> moved.add(o.stay().id()));
    Map<Long, Long> roomOfBed = new HashMap<>();
    beds.forEach(b -> roomOfBed.put(b.id(), b.roomId()));
    Set<Long> release = new HashSet<>();
    for (Op op : ops) {
      if (cards.get(op.stay().reservationId()).exclusiveRoomUse()) {
        continue;
      }
      for (ExistingStay o : existing) {
        if (!moved.contains(o.id())
            && o.exclusiveRoomUse()
            && o.reservationId() != op.stay().reservationId()
            && o.bedId() != op.target().bedId()
            && roomOfBed.get(o.bedId()) == op.target().roomId()
            && o.period().overlaps(op.period())) {
          release.add(o.reservationId());
        }
      }
    }
    return release;
  }

  /** Presúva sa len jeden člen malej skupiny (UC19), ak sa jeho spoločníci nepresúvajú tiež. */
  private List<Issue> partyWarnings(List<Op> ops) {
    List<Issue> out = new ArrayList<>();
    Set<Long> movedCards = new HashSet<>();
    ops.forEach(o -> movedCards.add(o.stay().reservationId()));
    for (Op op : ops) {
      if (op.kind() == Kind.RESIZE) {
        continue;
      }
      CardMeta meta = queries.cardMeta(op.stay().reservationId()).orElseThrow();
      if (meta.partyId() == null) {
        continue;
      }
      List<Long> others = queries.otherPartyCards(op.stay().reservationId(), meta.partyId());
      if (!others.isEmpty() && others.stream().noneMatch(movedCards::contains)) {
        out.add(
            new Issue(
                OccupancyCode.PARTY_MEMBER_MOVED,
                op.stay().id(),
                op.target().bedId(),
                "Presúva sa len jeden člen malej skupiny."));
      }
    }
    return out;
  }

  /** Oprávnenia platné vo všetkých dotknutých recepciách; limit nocí sa pri presune neuplatňuje. */
  private UserRights rights(List<Op> ops) {
    Set<Long> receptions = new HashSet<>();
    for (Op op : ops) {
      receptions.add(op.stay().receptionId());
      receptions.add(op.target().receptionId());
    }
    return new UserRights(
        all(receptions, Permissions.STAY_BACKDATE),
        all(receptions, Permissions.STAY_MOVE_LOCKED),
        all(receptions, Permissions.STAY_OTHER_CATEGORY),
        Permissions.MAX_LIMIT);
  }

  private boolean all(Set<Long> receptions, String permission) {
    return receptions.stream().allMatch(r -> permissions.has(permission, r));
  }

  private long priceCategory(CardRow card, RoomRow room) {
    String mode = queries.cardMeta(card.id()).orElseThrow().priceMode();
    return "KEEP_BOOKED".equals(mode) ? card.bookedCategoryId() : room.categoryId();
  }

  /** Pre každú dotknutú recepciu zverejní zmenu plánu (odošle sa až po COMMIT). */
  private void publishChanges(List<Op> ops) {
    Map<Long, Set<Long>> roomsByReception = new TreeMap<>();
    LocalDate from = null;
    LocalDate to = null;
    for (Op op : ops) {
      roomsByReception
          .computeIfAbsent(op.stay().receptionId(), k -> new TreeSet<>())
          .add(op.stay().roomId());
      if (op.target() != null) {
        roomsByReception
            .computeIfAbsent(op.target().receptionId(), k -> new TreeSet<>())
            .add(op.target().roomId());
      }
      from = min(from, min(op.stay().dateFrom(), op.period().from()));
      to = max(to, max(op.stay().dateTo(), op.period().to()));
    }
    Long auditEventId = AuditService.currentEventId();
    for (Map.Entry<Long, Set<Long>> e : roomsByReception.entrySet()) {
      events.publishEvent(
          new LiveEvent.PlanChanged(e.getKey(), List.copyOf(e.getValue()), from, to, auditEventId));
    }
  }

  private Plan plan(List<Op> ops) {
    Set<Long> roomIds = new TreeSet<>();
    LocalDate from = null;
    LocalDate to = null;
    for (Op op : ops) {
      roomIds.add(op.stay().roomId());
      if (op.target() != null) {
        roomIds.add(op.target().roomId());
      }
      from = min(from, min(op.stay().dateFrom(), op.period().from()));
      to = max(to, max(op.stay().dateTo(), op.period().to()));
    }
    return plans.roomsOf(List.copyOf(roomIds), new StayPeriod(from, to));
  }

  private void flush(Runnable write) {
    try {
      write.run();
    } catch (DataIntegrityViolationException e) {
      if (StayService.isExclusionViolation(e)) {
        throw new ApiException(
            HttpStatus.CONFLICT,
            OccupancyCode.BED_OCCUPIED.name(),
            "Lôžko je v intervale obsadené.");
      }
      throw e;
    }
  }

  private StayDetail detail(long id) {
    return queries
        .stayDetail(id)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Pobyt neexistuje."));
  }

  private BedRow targetBed(MoveItem item) {
    return queries
        .bed(item.targetBedId())
        .orElseThrow(() -> badRequest("Lôžko " + item.targetBedId() + " neexistuje."));
  }

  private CardRow activeCard(long id) {
    CardRow card =
        queries
            .card(id)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Karta neexistuje."));
    if (!ACTIVE_STATUSES.contains(card.status())) {
      throw new ApiException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "RESERVATION_NOT_ACTIVE",
          "Pobyt karty v stave " + card.status() + " nemožno meniť.");
    }
    return card;
  }

  private static ApiException stale() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "STALE_DATA",
        "Pobyt medzitým zmenil iný používateľ. Načítajte plán znova.");
  }

  private static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a == null || b.isBefore(a) ? b : a;
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a == null || b.isAfter(a) ? b : a;
  }
}
