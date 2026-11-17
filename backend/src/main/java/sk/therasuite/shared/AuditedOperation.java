package sk.therasuite.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Označuje zápisovú operáciu aplikačnej služby (HLD kap. 7.3). Aspekt otvorí transakciu a ako prvý
 * krok do nej vloží audit záznam; každá nová zápisová operácia musí túto anotáciu používať.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AuditedOperation {

  /** Kód operácie, napr. {@code USER_CREATE}. */
  String operation();

  /** Kód prípadu použitia, napr. {@code UC8} (nepovinné). */
  String useCase() default "";
}
