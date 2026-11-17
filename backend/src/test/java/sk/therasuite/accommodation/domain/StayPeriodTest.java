package sk.therasuite.accommodation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StayPeriodTest {

  private static LocalDate d(int day) {
    return LocalDate.of(2030, 1, day);
  }

  private static StayPeriod p(int from, int to) {
    return new StayPeriod(d(from), d(to));
  }

  @Test
  void invalidIntervalCannotBeCreated() {
    assertThatThrownBy(() -> p(5, 5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> p(6, 5)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StayPeriod(null, d(1))).isInstanceOf(NullPointerException.class);
  }

  @Test
  void countsNights() {
    assertThat(p(1, 2).nights()).isEqualTo(1);
    assertThat(p(1, 8).nights()).isEqualTo(7);
  }

  @Test
  void sameDayDepartureAndArrivalDoNotOverlap() {
    assertThat(p(1, 5).overlaps(p(5, 9))).isFalse();
    assertThat(p(5, 9).overlaps(p(1, 5))).isFalse();
  }

  @Test
  void overlapsDetectsOverlapAndContainment() {
    assertThat(p(1, 5).overlaps(p(4, 9))).isTrue();
    assertThat(p(4, 9).overlaps(p(1, 5))).isTrue();
    assertThat(p(1, 9).overlaps(p(3, 4))).isTrue();
    assertThat(p(1, 3).overlaps(p(5, 9))).isFalse();
  }

  @Test
  void containsIsHalfOpen() {
    assertThat(p(1, 5).contains(d(1))).isTrue();
    assertThat(p(1, 5).contains(d(4))).isTrue();
    assertThat(p(1, 5).contains(d(5))).isFalse();
    assertThat(p(1, 5).contains(d(31))).isFalse();
    assertThat(p(2, 5).contains(d(1))).isFalse();
  }

  @Test
  void splitsInside() {
    StayPeriod.Split split = p(1, 9).splitAt(d(4));
    assertThat(split.before()).isEqualTo(p(1, 4));
    assertThat(split.after()).isEqualTo(p(4, 9));
  }

  @Test
  void splitOnBoundaryOrOutsideFails() {
    assertThatThrownBy(() -> p(1, 9).splitAt(d(1))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> p(1, 9).splitAt(d(9))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> p(1, 9).splitAt(d(20))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void withEndChangesOnlyEnd() {
    assertThat(p(1, 9).withEnd(d(3))).isEqualTo(p(1, 3));
    assertThat(p(1, 9).withEnd(d(12))).isEqualTo(p(1, 12));
    assertThatThrownBy(() -> p(1, 9).withEnd(d(1))).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void intersection() {
    assertThat(p(1, 6).intersection(p(4, 9))).contains(p(4, 6));
    assertThat(p(1, 9).intersection(p(3, 4))).contains(p(3, 4));
    assertThat(p(3, 4).intersection(p(1, 9))).contains(p(3, 4));
    assertThat(p(1, 5).intersection(p(5, 9))).isEmpty();
  }
}
