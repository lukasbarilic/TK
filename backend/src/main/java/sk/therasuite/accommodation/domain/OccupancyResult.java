package sk.therasuite.accommodation.domain;

import java.util.List;

/** Výsledok kontroly: chyby zápis zastavia, upozornenia možno potvrdiť. */
public record OccupancyResult(List<Issue> errors, List<Issue> warnings) {

  /** Jeden nález; {@code stayId} je {@code null} pri novom pobyte. */
  public record Issue(OccupancyCode code, Long stayId, long bedId, String message) {}

  public OccupancyResult {
    errors = List.copyOf(errors);
    warnings = List.copyOf(warnings);
  }

  /** Bez chýb (upozornenia môžu byť). */
  public boolean isAllowed() {
    return errors.isEmpty();
  }

  /** Bez chýb, ale treba potvrdenie upozornení. */
  public boolean needsConfirmation() {
    return errors.isEmpty() && !warnings.isEmpty();
  }
}
