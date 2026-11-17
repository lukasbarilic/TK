package sk.therasuite.notification.application;

import java.time.LocalDate;
import java.util.List;

/**
 * Živá udalosť pre prehliadače (HLD kap. 6.4). Nesie len identifikátory a dátumy, nikdy osobné
 * údaje. Publikuje sa ako aplikačná udalosť a odosiela až po COMMIT.
 */
public sealed interface LiveEvent {

  /** Zmena pobytu v izbách jednej recepcie. */
  record PlanChanged(
      long receptionId, List<Long> roomIds, LocalDate from, LocalDate to, Long auditEventId)
      implements LiveEvent {}

  /** Nočná uzávierka recepcie; {@code businessDate} je nový obchodný deň. */
  record BusinessDayChanged(long receptionId, LocalDate businessDate) implements LiveEvent {}

  /** Udržiava spojenie cez proxy; dostanú ju všetci. */
  record Heartbeat() implements LiveEvent {}

  /** Názov udalosti v SSE (pole {@code event:}). */
  static String nameOf(LiveEvent event) {
    return switch (event) {
      case PlanChanged e -> "plan-changed";
      case BusinessDayChanged e -> "business-day-changed";
      case Heartbeat e -> "heartbeat";
    };
  }
}
