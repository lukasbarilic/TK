package sk.therasuite.audit.application;

import sk.therasuite.audit.domain.AuditEventType;
import sk.therasuite.audit.domain.AuditOutcome;

/**
 * Popis audit záznamu, ktorý sa má otvoriť. {@code actor == null} znamená prihláseného používateľa
 * z bezpečnostného kontextu.
 */
public record AuditEventSpec(
    AuditEventType type,
    String operation,
    String useCase,
    Actor actor,
    Object payload,
    AuditOutcome outcome,
    String errorCode) {

  /** Kto operáciu vykonal (userId môže chýbať, napr. pri prihlásení neznámeho používateľa). */
  public record Actor(Long userId, String username) {}

  /** Zápis pod prihláseným používateľom. */
  public static AuditEventSpec write(String operation, String useCase, Object payload) {
    return new AuditEventSpec(
        AuditEventType.WRITE, operation, useCase, null, payload, AuditOutcome.SUCCESS, null);
  }

  /** Zápis pod technickým používateľom {@code system} (štartovacie úlohy, demo dáta). */
  public static AuditEventSpec system(String operation) {
    return new AuditEventSpec(
        AuditEventType.WRITE,
        operation,
        null,
        new Actor(null, AuditService.SYSTEM_USERNAME),
        null,
        AuditOutcome.SUCCESS,
        null);
  }

  /** Prihlásenie (výsledok je známy vopred, lebo audit je len na pridávanie). */
  public static AuditEventSpec login(
      String operation, Actor actor, AuditOutcome outcome, String errorCode) {
    return new AuditEventSpec(
        AuditEventType.LOGIN, operation, null, actor, null, outcome, errorCode);
  }
}
