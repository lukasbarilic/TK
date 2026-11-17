package sk.therasuite.businessday;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import sk.therasuite.businessday.application.FixedBusinessDateProvider;

class FixedBusinessDateProviderTest {

  @Test
  void returnsFixedDayEverywhereAndCanBeChanged() {
    var provider = new FixedBusinessDateProvider(LocalDate.of(2030, 1, 10));
    assertThat(provider.current(1)).isEqualTo(LocalDate.of(2030, 1, 10));
    assertThat(provider.currentForRoom(5)).isEqualTo(LocalDate.of(2030, 1, 10));
    provider.set(LocalDate.of(2030, 2, 1));
    assertThat(provider.latestOf(List.of(1L, 2L))).isEqualTo(LocalDate.of(2030, 2, 1));
  }
}
