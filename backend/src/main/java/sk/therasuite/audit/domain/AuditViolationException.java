package sk.therasuite.audit.domain;

/** Pokus o zápis bez otvoreného audit záznamu (fail closed). */
public class AuditViolationException extends RuntimeException {

  public AuditViolationException(String message) {
    super(message);
  }
}
