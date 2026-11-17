package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import sk.therasuite.reservation.domain.StayPeriod;

class StayPeriodTest {

  private static final LocalDate DAY = LocalDate.of(2030, 1, 1);

  @Test
  void validPeriodHasNoViolation() {
    assertThat(StayPeriod.violation(DAY, DAY.plusDays(1))).isNull();
    assertThat(StayPeriod.violation(DAY, DAY.plusDays(365))).isNull();
  }

  @Test
  void arrivalMustBeBeforeDeparture() {
    assertThat(StayPeriod.violation(DAY, DAY)).isNotNull();
    assertThat(StayPeriod.violation(DAY, DAY.minusDays(1))).isNotNull();
  }

  @Test
  void moreThan365NightsIsRejected() {
    assertThat(StayPeriod.violation(DAY, DAY.plusDays(366))).isNotNull();
  }

  @Test
  void missingDatesAreRejected() {
    assertThat(StayPeriod.violation(null, DAY)).isNotNull();
    assertThat(StayPeriod.violation(DAY, null)).isNotNull();
  }
}
