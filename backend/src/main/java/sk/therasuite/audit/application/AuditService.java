package sk.therasuite.audit.application;

import jakarta.validation.ConstraintViolationException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.audit.application.AuditEventSpec.Actor;
import sk.therasuite.audit.domain.AuditEventType;
import sk.therasuite.audit.domain.AuditOutcome;
import sk.therasuite.audit.domain.AuditViolationException;
import sk.therasuite.audit.infrastructure.AuditContext;
import sk.therasuite.audit.infrastructure.AuditJson;
import sk.therasuite.audit.infrastructure.AuditRepository;
import sk.therasuite.audit.infrastructure.AuditRepository.NewEvent;
import sk.therasuite.audit.infrastructure.AuditRequestInfo;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.application.ApiException;

/**
 * Jadro auditu zápisov (HLD kap. 7.3): otvorí transakciu, ako prvý krok vloží audit_event a až
 * potom vykoná operáciu. Pri výnimke transakciu zruší a v novej transakcii (REQUIRES_NEW) zapíše
 * záznam s výsledkom REJECTED.
 */
@Service
public class AuditService {

  public static final String SYSTEM_USERNAME = "system";
  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  /** Auditovaná operácia; smie vyhodiť ľubovoľnú výnimku (aspekt ju vracia nezmenenú). */
  @FunctionalInterface
  public interface Body<T> {
    T run() throws Throwable;
  }

  private final PlatformTransactionManager transactionManager;
  private final AuditRepository repository;
  private final AuditJson json;

  public AuditService(
      PlatformTransactionManager transactionManager, AuditRepository repository, AuditJson json) {
    this.transactionManager = transactionManager;
    this.repository = repository;
    this.json = json;
  }

  /** Vykoná operáciu v auditovanej transakcii; výnimky vracia nezmenené. */
  public <T> T executeChecked(AuditEventSpec spec, Body<T> body) throws Throwable {
    if (AuditContext.get() != null) {
      return body.run(); // vnorená operácia patrí do už otvoreného audit záznamu
    }
    Actor actor = resolveActor(spec);
    String payload = spec.payload() == null ? null : json.toJson(spec.payload());
    AuditRequestInfo.Info request = AuditRequestInfo.current();
    TransactionStatus status =
        transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
      AuditContext.Current event =
          repository.insertEvent(
              newEvent(spec, actor, spec.outcome(), spec.errorCode(), payload, request));
      AuditContext.set(event);
      T result = body.run();
      transactionManager.commit(status);
      if (spec.type() == AuditEventType.WRITE && spec.outcome() == AuditOutcome.SUCCESS) {
        AuditRequestInfo.setEventIdHeader(event.eventId());
      }
      return result;
    } catch (Throwable t) {
      if (!status.isCompleted()) {
        transactionManager.rollback(status);
      }
      AuditContext.clear();
      recordRejected(spec, actor, payload, request, t);
      throw t;
    } finally {
      AuditContext.clear();
    }
  }

  public <T> T execute(AuditEventSpec spec, Supplier<T> body) {
    try {
      return executeChecked(spec, body::get);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new IllegalStateException(t);
    }
  }

  public void run(AuditEventSpec spec, Runnable body) {
    execute(
        spec,
        () -> {
          body.run();
          return null;
        });
  }

  private void recordRejected(
      AuditEventSpec spec,
      Actor actor,
      String payload,
      AuditRequestInfo.Info request,
      Throwable t) {
    try {
      TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
      requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
      requiresNew.executeWithoutResult(s ->
          repository.insertEvent(
              newEvent(spec, actor, AuditOutcome.REJECTED, errorCode(t), payload, request)));
    } catch (RuntimeException e) {
      log.error("Nepodarilo sa zapísať audit záznam REJECTED pre operáciu {}", spec.operation(), e);
    }
  }

  private NewEvent newEvent(
      AuditEventSpec spec,
      Actor actor,
      AuditOutcome outcome,
      String errorCode,
      String payload,
      AuditRequestInfo.Info request) {
    String roles = actor.userId() == null ? null : repository.rolesOf(actor.userId());
    return new NewEvent(
        spec.type().name(),
        actor.userId(),
        cut(actor.username(), 100),
        roles,
        spec.operation(),
        blankToNull(spec.useCase()),
        outcome.name(),
        errorCode,
        payload,
        request.requestId(),
        request.clientIp(),
        request.userAgent());
  }

  private Actor resolveActor(AuditEventSpec spec) {
    if (spec.actor() != null) {
      Actor a = spec.actor();
      if (a.userId() == null && SYSTEM_USERNAME.equals(a.username())) {
        return new Actor(repository.userIdByUsername(SYSTEM_USERNAME).orElseThrow(), a.username());
      }
      return a;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return new Actor(user.id(), user.username());
    }
    throw new AuditViolationException(
        "Auditovaná operácia " + spec.operation() + " vyžaduje prihláseného používateľa.");
  }

  /** Strojový kód chyby pre audit (podľa prvej rozpoznanej príčiny). */
  static String errorCode(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof ApiException e) {
        return e.getCode();
      }
      if (c instanceof AccessDeniedException) {
        return "FORBIDDEN";
      }
      if (c instanceof ConstraintViolationException) {
        return "VALIDATION_FAILED";
      }
      if (c instanceof OptimisticLockingFailureException) {
        return "CONFLICT";
      }
      if (c instanceof AuditViolationException) {
        return "AUDIT_REQUIRED";
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return "INTERNAL_ERROR";
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max);
  }
}
