package sk.therasuite.accommodation.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Polootvorený interval nocí {@code [from, to)}; {@code to} je deň odchodu (HLD kap. 5.1). Odchod
 * jedného a príchod druhého v ten istý deň nie je kolízia.
 */
public record StayPeriod(LocalDate from, LocalDate to) {

  /** Výsledok rozdelenia: {@code [from, d)} a {@code [d, to)}. */
  public record Split(StayPeriod before, StayPeriod after) {}

  public StayPeriod {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (!from.isBefore(to)) {
      throw new IllegalArgumentException(
          "Začiatok intervalu musí byť pred koncom: " + from + " – " + to);
    }
  }

  public long nights() {
    return ChronoUnit.DAYS.between(from, to);
  }

  public boolean overlaps(StayPeriod other) {
    return from.isBefore(other.to) && other.from.isBefore(to);
  }

  /** Noc daného dňa patrí do intervalu: {@code from ≤ date < to}. */
  public boolean contains(LocalDate date) {
    return !date.isBefore(from) && date.isBefore(to);
  }

  /**
   * Rozdelí interval v dni {@code date}, ktorý musí byť striktne vnútri ({@code from < date < to}).
   */
  public Split splitAt(LocalDate date) {
    if (!date.isAfter(from) || !date.isBefore(to)) {
      throw new IllegalArgumentException("Deň rozdelenia musí byť vnútri intervalu: " + date);
    }
    return new Split(new StayPeriod(from, date), new StayPeriod(date, to));
  }

  /** Rovnaký začiatok, nový koniec (musí byť po začiatku). */
  public StayPeriod withEnd(LocalDate newEnd) {
    return new StayPeriod(from, newEnd);
  }

  /** Prienik s iným intervalom, alebo prázdny, ak sa neprekrývajú. */
  public Optional<StayPeriod> intersection(StayPeriod other) {
    if (!overlaps(other)) {
      return Optional.empty();
    }
    return Optional.of(
        new StayPeriod(
            from.isAfter(other.from) ? from : other.from, to.isBefore(other.to) ? to : other.to));
  }
}
