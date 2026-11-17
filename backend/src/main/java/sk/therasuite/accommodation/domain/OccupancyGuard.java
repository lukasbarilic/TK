package sk.therasuite.accommodation.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.Context;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.accommodation.domain.OccupancyModel.Reservation;
import sk.therasuite.accommodation.domain.OccupancyModel.Room;
import sk.therasuite.accommodation.domain.OccupancyModel.RoomBlock;
import sk.therasuite.accommodation.domain.OccupancyModel.StayChange;
import sk.therasuite.accommodation.domain.OccupancyModel.UserRights;
import sk.therasuite.accommodation.domain.OccupancyResult.Issue;
import sk.therasuite.catalog.domain.BedType;

/**
 * Kontrola obsadenosti (HLD kap. 5.2). Overuje <b>konečný stav celej dávky</b> zmien: pobyty, ktoré
 * dávka mení, sa z existujúceho stavu nahradia navrhovanými, a až potom sa hľadajú kolízie (výmena
 * dvoch klientov preto prejde). Nekontroluje sa kolízia dvoch nezmenených pobytov. Služba nič
 * nenačítava; turnusy kontingentov sú mimo rozsahu.
 */
public final class OccupancyGuard {

  /** Pobyt v konečnom stave dávky. */
  private record Final(
      Long stayId, long reservationId, long bedId, StayPeriod period, boolean exclusive) {}

  public OccupancyResult check(Context ctx, List<StayChange> changes, UserRights rights) {
    Map<Long, Room> rooms = index(ctx.rooms(), Room::id);
    Map<Long, Bed> beds = index(ctx.beds(), Bed::id);
    Map<Long, Reservation> reservations = index(ctx.reservations(), Reservation::id);
    Map<Long, ExistingStay> existing = index(ctx.stays(), ExistingStay::id);

    List<Final> finalState = finalState(ctx, changes, existing, reservations);
    List<Issue> errors = new ArrayList<>();
    List<Issue> warnings = new ArrayList<>();

    for (int i = 0; i < changes.size(); i++) {
      StayChange c = changes.get(i);
      Bed bed = require(beds, c.bedId(), "lôžko");
      Room room = require(rooms, bed.roomId(), "izba");
      Reservation card = require(reservations, c.reservationId(), "karta");
      Final self = finalState.get(finalState.size() - changes.size() + i);

      checkAvailability(c, bed, room, card, errors);
      checkCollisions(c, self, bed, room, finalState, beds, ctx.blocks(), card, errors, warnings);
      checkBusinessDay(c, room, rights, errors);
      checkInsideReservation(c, card, errors);
      checkLocked(c, existing, card, rights, errors, warnings);
      checkLength(c, rights, errors);
      checkCategory(c, room, card, rights, errors);
    }
    return new OccupancyResult(errors, warnings);
  }

  private List<Final> finalState(
      Context ctx,
      List<StayChange> changes,
      Map<Long, ExistingStay> existing,
      Map<Long, Reservation> reservations) {
    List<Long> replaced = new ArrayList<>();
    for (StayChange c : changes) {
      if (c.stayId() != null) {
        require(existing, c.stayId(), "pobyt");
        replaced.add(c.stayId());
      }
    }
    List<Final> result = new ArrayList<>();
    for (ExistingStay s : ctx.stays()) {
      if (!replaced.contains(s.id())) {
        result.add(
            new Final(s.id(), s.reservationId(), s.bedId(), s.period(), s.exclusiveRoomUse()));
      }
    }
    // Zmeny idú na koniec, v poradí vstupu (pozri check).
    for (StayChange c : changes) {
      Reservation card = require(reservations, c.reservationId(), "karta");
      result.add(
          new Final(c.stayId(), c.reservationId(), c.bedId(), c.period(), card.exclusiveRoomUse()));
    }
    return result;
  }

  private void checkAvailability(
      StayChange c, Bed bed, Room room, Reservation card, List<Issue> errors) {
    if (!bed.active() || !room.active() || !room.bookable()) {
      errors.add(issue(OccupancyCode.BED_UNAVAILABLE, c, "Lôžko alebo izba nie je dostupná."));
    }
    if (bed.type() == BedType.EXTRA && room.extraBedMaxAge() != null) {
      boolean tooOld = card.guestAges().stream().anyMatch(age -> age > room.extraBedMaxAge());
      if (tooOld) {
        errors.add(
            issue(
                OccupancyCode.EXTRA_BED_AGE,
                c,
                "Prístelka je len pre hostí do veku " + room.extraBedMaxAge() + " rokov."));
      }
    }
  }

  private void checkCollisions(
      StayChange c,
      Final self,
      Bed bed,
      Room room,
      List<Final> finalState,
      Map<Long, Bed> beds,
      List<RoomBlock> blocks,
      Reservation card,
      List<Issue> errors,
      List<Issue> warnings) {
    for (RoomBlock b : blocks) {
      if (b.roomId() == room.id() && b.period().overlaps(c.period())) {
        errors.add(issue(OccupancyCode.ROOM_BLOCKED, c, "Izba je v intervale blokovaná."));
        break;
      }
    }
    boolean bedOccupied = false;
    boolean roomNotEmpty = false;
    boolean releasesHeld = false;
    for (Final o : finalState) {
      if (o == self || !o.period().overlaps(c.period())) {
        continue;
      }
      Bed ob = beds.get(o.bedId());
      if (ob == null || ob.roomId() != room.id()) {
        continue;
      }
      if (o.bedId() == bed.id()) {
        bedOccupied = true;
      }
      if (o.reservationId() != c.reservationId()) {
        if (card.exclusiveRoomUse()) {
          roomNotEmpty = true;
        } else if (o.exclusive() && o.bedId() != bed.id()) {
          releasesHeld = true;
        }
      }
    }
    if (bedOccupied) {
      errors.add(issue(OccupancyCode.BED_OCCUPIED, c, "Lôžko je v intervale obsadené."));
    }
    if (roomNotEmpty) {
      errors.add(
          issue(
              OccupancyCode.ROOM_NOT_EMPTY,
              c,
              "Samostatné bývanie vyžaduje v intervale úplne prázdnu izbu."));
    }
    if (releasesHeld) {
      warnings.add(
          issue(
              OccupancyCode.RELEASES_HELD_BED,
              c,
              "Lôžko je držané samostatným bývaním inej karty; ubytovaním sa uvoľní."));
    }
  }

  private void checkBusinessDay(StayChange c, Room room, UserRights rights, List<Issue> errors) {
    if (c.effectiveFrom().isBefore(room.businessDay()) && !rights.backdate()) {
      errors.add(
          issue(OccupancyCode.PAST_CHANGE, c, "Zmena začína pred aktuálnym obchodným dňom."));
    }
  }

  private void checkInsideReservation(StayChange c, Reservation card, List<Issue> errors) {
    if (c.effectiveFrom().isBefore(card.arrivalDate())
        || !c.effectiveFrom().isBefore(card.departureDate())) {
      errors.add(
          issue(
              OccupancyCode.OUTSIDE_RESERVATION,
              c,
              "Deň zmeny je mimo termínu rezervačnej karty."));
    }
  }

  private void checkLocked(
      StayChange c,
      Map<Long, ExistingStay> existing,
      Reservation card,
      UserRights rights,
      List<Issue> errors,
      List<Issue> warnings) {
    if (c.stayId() == null) {
      return;
    }
    if (existing.get(c.stayId()).locked() || card.doNotMove()) {
      if (rights.moveLocked()) {
        warnings.add(issue(OccupancyCode.STAY_LOCKED, c, "Pobyt je zamknutý (nepresúvať)."));
      } else {
        errors.add(
            issue(
                OccupancyCode.MISSING_PERMISSION,
                c,
                "Chýba oprávnenie STAY_MOVE_LOCKED na zmenu zamknutého pobytu."));
      }
    }
  }

  private void checkLength(StayChange c, UserRights rights, List<Issue> errors) {
    // Limit z STAY_CREATE sa týka ubytovania (nový pobyt), nie presunu existujúceho.
    if (c.stayId() == null && c.period().nights() > rights.maxStayNights()) {
      errors.add(
          issue(
              OccupancyCode.MAX_NIGHTS_EXCEEDED,
              c,
              "Pobyt presahuje povolený limit " + rights.maxStayNights() + " nocí."));
    }
  }

  private void checkCategory(
      StayChange c, Room room, Reservation card, UserRights rights, List<Issue> errors) {
    boolean matches =
        room.categoryId() == card.bookedCategoryId()
            || (room.secondaryCategoryId() != null
                && room.secondaryCategoryId() == card.bookedCategoryId());
    if (!matches && !rights.otherCategory()) {
      errors.add(
          issue(OccupancyCode.CATEGORY_MISMATCH, c, "Izba má inú kategóriu než rezervačná karta."));
    }
  }

  private static Issue issue(OccupancyCode code, StayChange c, String message) {
    return new Issue(code, c.stayId(), c.bedId(), message);
  }

  private static <T> Map<Long, T> index(List<T> items, java.util.function.ToLongFunction<T> id) {
    Map<Long, T> map = new HashMap<>();
    for (T item : items) {
      map.put(id.applyAsLong(item), item);
    }
    return map;
  }

  private static <T> T require(Map<Long, T> map, long id, String what) {
    T value = map.get(id);
    if (value == null) {
      throw new IllegalArgumentException("Chýba vstupný záznam: " + what + " " + id);
    }
    return value;
  }
}
