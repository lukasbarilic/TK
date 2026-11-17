package sk.therasuite.reservation.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pravidlá termínu karty: príchod pred odchodom, najviac {@value #MAX_NIGHTS} nocí. */
public final class StayPeriod {

  public static final int MAX_NIGHTS = 365;

  private StayPeriod() {}

  /** Vráti text chyby, alebo {@code null}, ak je termín platný. */
  public static String violation(LocalDate arrival, LocalDate departure) {
    if (arrival == null || departure == null) {
      return "Dátum príchodu a odchodu sú povinné.";
    }
    if (!arrival.isBefore(departure)) {
      return "Dátum príchodu musí byť pred dátumom odchodu.";
    }
    if (ChronoUnit.DAYS.between(arrival, departure) > MAX_NIGHTS) {
      return "Pobyt môže mať najviac " + MAX_NIGHTS + " nocí.";
    }
    return null;
  }
}
