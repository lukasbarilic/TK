package sk.therasuite.accommodation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.Context;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.accommodation.domain.OccupancyModel.Reservation;
import sk.therasuite.accommodation.domain.OccupancyModel.Room;
import sk.therasuite.accommodation.domain.OccupancyModel.RoomBlock;
import sk.therasuite.accommodation.domain.OccupancyModel.StayChange;
import sk.therasuite.accommodation.domain.OccupancyModel.UserRights;
import sk.therasuite.catalog.domain.BedType;

class OccupancyGuardTest {

  private static final long CAT = 1;
  private static final LocalDate TODAY = d(10);
  private static final UserRights REC = new UserRights(false, false, false, 365);
  private static final UserRights MGR = new UserRights(true, true, true, 365);

  private final OccupancyGuard guard = new OccupancyGuard();

  private static LocalDate d(int day) {
    return LocalDate.of(2030, 1, 1).plusDays(day - 1L);
  }

  private static StayPeriod p(int from, int to) {
    return new StayPeriod(d(from), d(to));
  }

  private static Room room(long id) {
    return new Room(id, CAT, null, true, true, 10, TODAY);
  }

  private static Bed bed(long id, long roomId) {
    return new Bed(id, roomId, BedType.REGULAR, true);
  }

  private static Reservation card(long id, boolean exclusive) {
    return new Reservation(id, CAT, d(10), d(30), exclusive, false, List.of(40));
  }

  private static ExistingStay stay(long id, long card, long bed, int from, int to) {
    return new ExistingStay(id, card, bed, p(from, to), false, false);
  }

  /** Izba 1 s lôžkami 11, 12 a prístelkou 13; izba 2 s lôžkom 21. Karty 1–3, 9 = exkluzívna. */
  private Context ctx(List<ExistingStay> stays, List<RoomBlock> blocks) {
    return ctx(room(1), stays, blocks, card(1, false), card(2, false), card(3, false));
  }

  private Context ctx(
      Room room1, List<ExistingStay> stays, List<RoomBlock> blocks, Reservation... reservations) {
    List<Reservation> all = new java.util.ArrayList<>(List.of(reservations));
    all.add(card(9, true));
    return new Context(
        List.of(room1, room(2)),
        List.of(bed(11, 1), bed(12, 1), new Bed(13, 1, BedType.EXTRA, true), bed(21, 2)),
        stays,
        blocks,
        all);
  }

  private List<OccupancyCode> errors(OccupancyResult r) {
    return r.errors().stream().map(OccupancyResult.Issue::code).toList();
  }

  private List<OccupancyCode> warnings(OccupancyResult r) {
    return r.warnings().stream().map(OccupancyResult.Issue::code).toList();
  }

  private OccupancyResult create(Context c, long card, long bed, int from, int to, UserRights u) {
    return guard.check(c, List.of(StayChange.create(card, bed, p(from, to))), u);
  }

  @Test
  void validNewStayPasses() {
    OccupancyResult r = create(ctx(List.of(), List.of()), 1, 11, 10, 15, REC);
    assertThat(r.isAllowed()).isTrue();
    assertThat(r.needsConfirmation()).isFalse();
    assertThat(r.warnings()).isEmpty();
  }

  @Test
  void inactiveBedRoomOrNotBookableIsUnavailable() {
    Context inactiveBed =
        new Context(
            List.of(room(1)),
            List.of(new Bed(11, 1, BedType.REGULAR, false)),
            List.of(),
            List.of(),
            List.of(card(1, false)));
    assertThat(errors(create(inactiveBed, 1, 11, 10, 12, REC)))
        .containsExactly(OccupancyCode.BED_UNAVAILABLE);

    Room inactiveRoom = new Room(1, CAT, null, false, true, 10, TODAY);
    assertThat(
            errors(
                create(
                    ctx(inactiveRoom, List.of(), List.of(), card(1, false)), 1, 11, 10, 12, REC)))
        .containsExactly(OccupancyCode.BED_UNAVAILABLE);

    Room notBookable = new Room(1, CAT, null, true, false, 10, TODAY);
    assertThat(
            errors(
                create(ctx(notBookable, List.of(), List.of(), card(1, false)), 1, 11, 10, 12, REC)))
        .containsExactly(OccupancyCode.BED_UNAVAILABLE);
  }

  @Test
  void extraBedChecksGuestAge() {
    Context c = ctx(List.of(), List.of());
    // hosť má 40, limit prístelky 10
    assertThat(errors(create(c, 1, 13, 10, 12, REC))).containsExactly(OccupancyCode.EXTRA_BED_AGE);

    Reservation child = new Reservation(1, CAT, d(10), d(30), false, false, List.of(8, 10));
    Context ok = ctx(room(1), List.of(), List.of(), child);
    assertThat(create(ok, 1, 13, 10, 12, REC).isAllowed()).isTrue();

    Room noLimit = new Room(1, CAT, null, true, true, null, TODAY);
    assertThat(
            create(ctx(noLimit, List.of(), List.of(), card(1, false)), 1, 13, 10, 12, REC)
                .isAllowed())
        .isTrue();

    // limit sa týka len prístelky
    assertThat(create(c, 1, 11, 10, 12, REC).isAllowed()).isTrue();
  }

  @Test
  void bedCollisionIsError() {
    Context c = ctx(List.of(stay(100, 2, 11, 10, 15)), List.of());
    assertThat(errors(create(c, 1, 11, 14, 18, REC))).containsExactly(OccupancyCode.BED_OCCUPIED);
  }

  @Test
  void sameDayDepartureAndArrivalIsNotCollision() {
    Context c = ctx(List.of(stay(100, 2, 11, 10, 15)), List.of());
    assertThat(create(c, 1, 11, 15, 18, REC).isAllowed()).isTrue();
    Context before = ctx(List.of(stay(100, 2, 11, 15, 20)), List.of());
    assertThat(create(before, 1, 11, 10, 15, REC).isAllowed()).isTrue();
  }

  @Test
  void otherBedAndOtherRoomDoNotCollide() {
    Context c = ctx(List.of(stay(100, 2, 11, 10, 15)), List.of());
    assertThat(create(c, 1, 12, 10, 15, REC).isAllowed()).isTrue();
    assertThat(create(c, 1, 21, 10, 15, REC).isAllowed()).isTrue();
  }

  @Test
  void blockCollisionIsError() {
    Context c = ctx(List.of(), List.of(new RoomBlock(5, 1, p(12, 14))));
    assertThat(errors(create(c, 1, 11, 10, 13, REC))).containsExactly(OccupancyCode.ROOM_BLOCKED);
    assertThat(create(c, 1, 11, 14, 16, REC).isAllowed()).isTrue();
    assertThat(create(c, 1, 21, 10, 13, REC).isAllowed()).isTrue();
  }

  @Test
  void stayInHeldRoomWarnsAboutReleasingHeldBed() {
    ExistingStay exclusive = new ExistingStay(100, 9, 11, p(10, 20), false, true);
    Context c = ctx(List.of(exclusive), List.of());
    OccupancyResult r = create(c, 1, 12, 12, 15, REC);
    assertThat(r.isAllowed()).isTrue();
    assertThat(r.needsConfirmation()).isTrue();
    assertThat(warnings(r)).containsExactly(OccupancyCode.RELEASES_HELD_BED);
    // na lôžku samotného exkluzívneho pobytu je to chyba
    assertThat(errors(create(c, 1, 11, 12, 15, REC))).containsExactly(OccupancyCode.BED_OCCUPIED);
    // mimo intervalu bez upozornenia
    assertThat(create(c, 1, 12, 20, 22, REC).warnings()).isEmpty();
  }

  @Test
  void exclusiveCardNeedsEmptyRoom() {
    Context c = ctx(List.of(stay(100, 2, 12, 10, 15)), List.of());
    assertThat(errors(create(c, 9, 11, 12, 18, REC))).containsExactly(OccupancyCode.ROOM_NOT_EMPTY);
    assertThat(create(c, 9, 11, 15, 18, REC).isAllowed()).isTrue();
  }

  @Test
  void exclusiveCardDoesNotConflictWithItsOwnStays() {
    ExistingStay own = new ExistingStay(100, 9, 11, p(10, 15), false, true);
    Context c = ctx(List.of(own), List.of());
    assertThat(create(c, 9, 11, 15, 18, REC).isAllowed()).isTrue();
    assertThat(create(c, 9, 12, 12, 14, REC).errors()).isEmpty();
  }

  @Test
  void pastChangeNeedsBackdatePermission() {
    Context c = ctx(List.of(), List.of());
    assertThat(errors(create(c, 1, 11, 9, 12, REC)))
        .contains(OccupancyCode.PAST_CHANGE, OccupancyCode.OUTSIDE_RESERVATION);
    Reservation early = new Reservation(1, CAT, d(5), d(30), false, false, List.of(40));
    Context c2 = ctx(room(1), List.of(), List.of(), early);
    assertThat(errors(create(c2, 1, 11, 9, 12, REC))).containsExactly(OccupancyCode.PAST_CHANGE);
    assertThat(create(c2, 1, 11, 9, 12, MGR).isAllowed()).isTrue();
    // obchodný deň samotný je ešte v poriadku
    assertThat(create(c2, 1, 11, 10, 12, REC).isAllowed()).isTrue();
  }

  @Test
  void changeDayMustBeInsideReservation() {
    Context c = ctx(List.of(), List.of());
    // karta [10, 30)
    assertThat(errors(create(c, 1, 11, 30, 33, REC)))
        .containsExactly(OccupancyCode.OUTSIDE_RESERVATION);
    assertThat(create(c, 1, 11, 29, 33, REC).isAllowed()).isTrue();
    assertThat(create(c, 1, 11, 10, 33, REC).isAllowed()).isTrue();
  }

  @Test
  void lockedStayWarnsWithPermissionAndFailsWithout() {
    ExistingStay locked = new ExistingStay(100, 1, 11, p(10, 20), true, false);
    Context c = ctx(List.of(locked), List.of());
    List<StayChange> move = List.of(StayChange.update(100, 1, 12, p(10, 20), d(10)));

    OccupancyResult withPerm = guard.check(c, move, MGR);
    assertThat(withPerm.isAllowed()).isTrue();
    assertThat(warnings(withPerm)).containsExactly(OccupancyCode.STAY_LOCKED);

    OccupancyResult without = guard.check(c, move, REC);
    assertThat(errors(without)).containsExactly(OccupancyCode.MISSING_PERMISSION);
    assertThat(without.warnings()).isEmpty();
  }

  @Test
  void doNotMoveCardBehavesLikeLockedStay() {
    Reservation fixed = new Reservation(1, CAT, d(10), d(30), false, true, List.of(40));
    Context c = ctx(room(1), List.of(stay(100, 1, 11, 10, 20)), List.of(), fixed);
    List<StayChange> move = List.of(StayChange.update(100, 1, 12, p(10, 20), d(10)));
    assertThat(warnings(guard.check(c, move, MGR))).containsExactly(OccupancyCode.STAY_LOCKED);
    assertThat(errors(guard.check(c, move, REC))).containsExactly(OccupancyCode.MISSING_PERMISSION);
    // nový pobyt na karte do_not_move nie je presun
    assertThat(create(c, 1, 12, 20, 22, REC).isAllowed()).isTrue();
  }

  @Test
  void newStayLongerThanLimitIsRejected() {
    Context c = ctx(List.of(), List.of());
    UserRights limited = new UserRights(false, false, false, 7);
    assertThat(errors(create(c, 1, 11, 10, 18, limited)))
        .containsExactly(OccupancyCode.MAX_NIGHTS_EXCEEDED);
    assertThat(create(c, 1, 11, 10, 17, limited).isAllowed()).isTrue();
  }

  @Test
  void limitDoesNotApplyToMovingExistingStay() {
    Context c = ctx(List.of(stay(100, 1, 11, 10, 25)), List.of());
    UserRights limited = new UserRights(false, false, false, 7);
    List<StayChange> move = List.of(StayChange.update(100, 1, 12, p(10, 25), d(10)));
    assertThat(guard.check(c, move, limited).isAllowed()).isTrue();
  }

  @Test
  void otherCategoryNeedsPermission() {
    Room other = new Room(1, 2, null, true, true, 10, TODAY);
    Context c = ctx(other, List.of(), List.of(), card(1, false));
    assertThat(errors(create(c, 1, 11, 10, 12, REC)))
        .containsExactly(OccupancyCode.CATEGORY_MISMATCH);
    assertThat(create(c, 1, 11, 10, 12, MGR).isAllowed()).isTrue();

    Room secondary = new Room(1, 2, CAT, true, true, 10, TODAY);
    Context c2 = ctx(secondary, List.of(), List.of(), card(1, false));
    assertThat(create(c2, 1, 11, 10, 12, REC).isAllowed()).isTrue();
  }

  @Test
  void swapOfTwoClientsPassesAsWholeBatch() {
    Context c = ctx(List.of(stay(100, 1, 11, 10, 20), stay(101, 2, 12, 10, 20)), List.of());
    List<StayChange> swap =
        List.of(
            StayChange.update(100, 1, 12, p(10, 20), d(10)),
            StayChange.update(101, 2, 11, p(10, 20), d(10)));
    assertThat(guard.check(c, swap, REC).isAllowed()).isTrue();
    // jediný krok samostatne by zlyhal
    assertThat(errors(guard.check(c, List.of(swap.get(0)), REC)))
        .containsExactly(OccupancyCode.BED_OCCUPIED);
  }

  @Test
  void batchCollisionBetweenTwoChangesIsDetected() {
    Context c = ctx(List.of(), List.of());
    OccupancyResult r =
        guard.check(
            c,
            List.of(StayChange.create(1, 11, p(10, 15)), StayChange.create(2, 11, p(12, 18))),
            REC);
    assertThat(errors(r)).containsOnly(OccupancyCode.BED_OCCUPIED);
    assertThat(r.errors()).hasSize(2);
  }

  @Test
  void splitStayOnOtherBedPasses() {
    Context c = ctx(List.of(stay(100, 1, 11, 10, 20)), List.of());
    StayPeriod.Split split = p(10, 20).splitAt(d(15));
    List<StayChange> batch =
        List.of(
            StayChange.update(100, 1, 11, split.before(), d(15)),
            new StayChange(null, 1, 12, split.after(), d(15)));
    assertThat(guard.check(c, batch, REC).isAllowed()).isTrue();
  }

  @Test
  void splitOntoOccupiedBedFails() {
    Context c = ctx(List.of(stay(100, 1, 11, 10, 20), stay(101, 2, 12, 17, 20)), List.of());
    StayPeriod.Split split = p(10, 20).splitAt(d(15));
    List<StayChange> batch =
        List.of(
            StayChange.update(100, 1, 11, split.before(), d(15)),
            new StayChange(null, 1, 12, split.after(), d(15)));
    assertThat(errors(guard.check(c, batch, REC))).containsExactly(OccupancyCode.BED_OCCUPIED);
  }

  @Test
  void shorteningFreesBedForSameBatch() {
    Context c = ctx(List.of(stay(100, 1, 11, 10, 20)), List.of());
    List<StayChange> batch =
        List.of(
            StayChange.update(100, 1, 11, p(10, 20).withEnd(d(15)), d(15)),
            StayChange.create(2, 11, p(15, 20)));
    assertThat(guard.check(c, batch, REC).isAllowed()).isTrue();
  }

  @Test
  void unknownReferencesAreRejected() {
    Context c = ctx(List.of(), List.of());
    assertThatThrownBy(() -> create(c, 1, 99, 10, 12, REC))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> create(c, 99, 11, 10, 12, REC))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> guard.check(c, List.of(StayChange.update(77, 1, 11, p(10, 12), d(10))), REC))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resultCopiesListsAndExposesStates() {
    OccupancyResult r = new OccupancyResult(List.of(), List.of());
    assertThat(r.isAllowed()).isTrue();
    assertThat(r.needsConfirmation()).isFalse();
    assertThat(OccupancyCode.STAY_LOCKED.isWarning()).isTrue();
    assertThat(OccupancyCode.BED_OCCUPIED.isWarning()).isFalse();
  }
}
