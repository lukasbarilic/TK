package sk.therasuite.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Označuje čítaciu metódu aplikačnej služby, ktorá vracia osobné údaje klienta (HLD kap. 7.4).
 * Metóda musí vrátiť {@link AuditedReadResult}; aspekt pred odoslaním odpovede zapíše audit záznam
 * READ a ID klientov. Každý endpoint s osobnými údajmi musí túto anotáciu používať.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditedRead {

  /** Kód operácie, napr. {@code RESERVATION_VIEW}. */
  String operation();

  /** Rozsah vrátených osobných údajov. */
  DataScope scope();
}
