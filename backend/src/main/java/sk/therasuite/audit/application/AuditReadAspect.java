package sk.therasuite.audit.application;

import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import sk.therasuite.audit.domain.AuditViolationException;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;

/**
 * Aspekt nad metódami s {@link AuditedRead}. Po načítaní dát a pred návratom do controllera zapíše
 * audit čítania; ak zápis zlyhá, výnimka zabráni odoslaniu dát (HTTP 500).
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditReadAspect {

  private final AuditService audit;

  public AuditReadAspect(AuditService audit) {
    this.audit = audit;
  }

  @Around("@annotation(sk.therasuite.shared.AuditedRead)")
  public Object audited(ProceedingJoinPoint joinPoint) throws Throwable {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    AuditedRead read = AnnotatedElementUtils.findMergedAnnotation(method, AuditedRead.class);
    if (read == null) {
      Method target =
          joinPoint.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
      read = AnnotatedElementUtils.findMergedAnnotation(target, AuditedRead.class);
    }
    Object result = joinPoint.proceed();
    if (!(result instanceof AuditedReadResult<?> readResult)) {
      throw new AuditViolationException(
          "Metóda s @AuditedRead (" + read.operation() + ") musí vrátiť AuditedReadResult.");
    }
    audit.recordRead(read.operation(), read.scope(), readResult.clientIds());
    return result;
  }
}
