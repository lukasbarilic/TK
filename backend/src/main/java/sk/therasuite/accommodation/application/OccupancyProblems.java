package sk.therasuite.accommodation.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import sk.therasuite.accommodation.domain.OccupancyCode;
import sk.therasuite.accommodation.domain.OccupancyResult;
import sk.therasuite.accommodation.domain.OccupancyResult.Issue;
import sk.therasuite.shared.application.ApiException;

/** Preklad výsledku kontroly obsadenosti na chyby API (HLD kap. 6.3). */
final class OccupancyProblems {

  static final String CONFIRMATION_REQUIRED = "CONFIRMATION_REQUIRED";

  private OccupancyProblems() {}

  /** Obsadenosť a blokácie sú konflikt stavu (409), ostatné porušenia pravidiel sú 422. */
  static HttpStatus statusOf(OccupancyCode code) {
    return switch (code) {
      case BED_OCCUPIED,
          ROOM_BLOCKED,
          ROOM_NOT_EMPTY,
          BED_UNAVAILABLE,
          RELEASES_HELD_BED,
          STAY_LOCKED ->
          HttpStatus.CONFLICT;
      default -> HttpStatus.UNPROCESSABLE_ENTITY;
    };
  }

  /**
   * Vyhodí chybu pri blokujúcich nálezoch; pri nepotvrdených upozorneniach vyhodí 409 s {@code
   * confirmable = true}. Potvrdené upozornenia ({@code confirm} obsahuje ich kód) prejdú.
   */
  static void enforce(OccupancyResult result, List<String> confirm) {
    if (!result.errors().isEmpty()) {
      Issue first = result.errors().getFirst();
      throw new ApiException(
          statusOf(first.code()),
          first.code().name(),
          first.message(),
          Map.of("errors", result.errors().stream().map(OccupancyProblems::view).toList()));
    }
    List<Issue> unconfirmed =
        result.warnings().stream().filter(w -> !confirm.contains(w.code().name())).toList();
    if (!unconfirmed.isEmpty()) {
      Map<String, Object> extensions = new LinkedHashMap<>();
      extensions.put("confirmable", true);
      extensions.put("warnings", unconfirmed.stream().map(OccupancyProblems::view).toList());
      throw new ApiException(
          HttpStatus.CONFLICT,
          CONFIRMATION_REQUIRED,
          "Operácia vyžaduje potvrdenie upozornení.",
          extensions);
    }
  }

  private static Map<String, Object> view(Issue issue) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("code", issue.code().name());
    out.put("message", issue.message());
    out.put("bedId", issue.bedId());
    return out;
  }
}
