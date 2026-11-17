package sk.therasuite.accommodation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import sk.therasuite.accommodation.domain.HeldBedCalculator.HeldBed;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.catalog.domain.BedType;

class HeldBedCalculatorTest {

  private final HeldBedCalculator calculator = new HeldBedCalculator();
  private final List<Bed> beds =
      List.of(
          new Bed(11, 1, BedType.REGULAR, true),
          new Bed(12, 1, BedType.REGULAR, true),
          new Bed(13, 1, BedType.REGULAR, false),
          new Bed(14, 1, BedType.EXTRA, true));

  private static StayPeriod p(int from, int to) {
    return new StayPeriod(LocalDate.of(2030, 1, from), LocalDate.of(2030, 1, to));
  }

  private static ExistingStay stay(long id, long bed, int from, int to, boolean exclusive) {
    return new ExistingStay(id, 9, bed, p(from, to), false, exclusive);
  }

  @Test
  void exclusiveStayHoldsRemainingActiveRegularBeds() {
    List<HeldBed> held = calculator.calculate(beds, List.of(stay(1, 11, 10, 20, true)), p(1, 30));
    assertThat(held).containsExactly(new HeldBed(12, p(10, 20), 9));
  }

  @Test
  void nonExclusiveStayHoldsNothing() {
    assertThat(calculator.calculate(beds, List.of(stay(1, 11, 10, 20, false)), p(1, 30))).isEmpty();
  }

  @Test
  void resultIsClippedToInterval() {
    List<HeldBed> held = calculator.calculate(beds, List.of(stay(1, 11, 10, 20, true)), p(15, 18));
    assertThat(held).containsExactly(new HeldBed(12, p(15, 18), 9));
  }

  @Test
  void stayOutsideIntervalHoldsNothing() {
    assertThat(calculator.calculate(beds, List.of(stay(1, 11, 10, 20, true)), p(20, 25))).isEmpty();
  }

  @Test
  void movingWithinRoomSwapsHeldBedAndMergesAdjacentParts() {
    List<ExistingStay> stays = List.of(stay(1, 11, 10, 15, true), stay(2, 12, 15, 20, true));
    List<HeldBed> held = calculator.calculate(beds, stays, p(1, 30));
    assertThat(held).containsExactly(new HeldBed(11, p(15, 20), 9), new HeldBed(12, p(10, 15), 9));
  }

  @Test
  void adjacentPartsOnSameHeldBedAreMerged() {
    List<Bed> three =
        List.of(
            new Bed(11, 1, BedType.REGULAR, true),
            new Bed(12, 1, BedType.REGULAR, true),
            new Bed(15, 1, BedType.REGULAR, true));
    List<ExistingStay> stays = List.of(stay(1, 11, 10, 15, true), stay(2, 12, 15, 20, true));
    assertThat(calculator.calculate(three, stays, p(1, 30)))
        .contains(new HeldBed(15, p(10, 20), 9));
  }

  @Test
  void gapBetweenPartsKeepsThemSeparate() {
    List<ExistingStay> stays = List.of(stay(1, 11, 10, 12, true), stay(2, 11, 15, 20, true));
    assertThat(calculator.calculate(beds, stays, p(1, 30)))
        .containsExactly(new HeldBed(12, p(10, 12), 9), new HeldBed(12, p(15, 20), 9));
  }
}
