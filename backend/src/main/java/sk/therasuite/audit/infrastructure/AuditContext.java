package sk.therasuite.audit.infrastructure;

import java.time.Instant;

/**
 * Audit záznam otvorený pre aktuálne vlákno (platí počas celej transakcie auditovanej operácie).
 */
public final class AuditContext {

  /** Otvorený audit záznam: identifikátor a čas (kľúč partície). */
  public record Current(long eventId, Instant occurredAt) {}

  private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();

  private AuditContext() {}

  public static Current get() {
    return CURRENT.get();
  }

  public static void set(Current current) {
    CURRENT.set(current);
  }

  public static void clear() {
    CURRENT.remove();
  }
}
