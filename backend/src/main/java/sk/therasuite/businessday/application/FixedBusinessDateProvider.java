package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.Collection;

/** Poskytovateľ s pevným dňom pre všetky recepcie; určený pre testy. */
public class FixedBusinessDateProvider implements BusinessDateProvider {

  private volatile LocalDate day;

  public FixedBusinessDateProvider(LocalDate day) {
    this.day = day;
  }

  public void set(LocalDate day) {
    this.day = day;
  }

  @Override
  public LocalDate current(long receptionId) {
    return day;
  }

  @Override
  public LocalDate currentForRoom(long roomId) {
    return day;
  }

  @Override
  public LocalDate latestOf(Collection<Long> roomIds) {
    return day;
  }
}
