package sk.therasuite.audit.application;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import sk.therasuite.shared.AuditedOperation;

/**
 * Aspekt nad metódami s {@link AuditedOperation}. Beží pred transakciou služby (najvyššia
 * precedencia), takže audit záznam vzniká ako prvý krok transakcie.
 */
@Aspect
@Component
@EnableAspectJAutoProxy
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditAspect {

  private final AuditService audit;

  public AuditAspect(AuditService audit) {
    this.audit = audit;
  }

  @Around("@annotation(sk.therasuite.shared.AuditedOperation)")
  public Object audited(ProceedingJoinPoint joinPoint) throws Throwable {
    Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
    AuditedOperation operation =
        AnnotatedElementUtils.findMergedAnnotation(method, AuditedOperation.class);
    if (operation == null) {
      Method target =
          joinPoint.getTarget().getClass().getMethod(method.getName(), method.getParameterTypes());
      operation = AnnotatedElementUtils.findMergedAnnotation(target, AuditedOperation.class);
    }
    AuditEventSpec spec =
        AuditEventSpec.write(operation.operation(), operation.useCase(), payload(joinPoint));
    return audit.executeChecked(spec, joinPoint::proceed);
  }

  /** Argumenty metódy podľa mien parametrov; polia s heslami odstráni AuditJson. */
  private static Map<String, Object> payload(ProceedingJoinPoint joinPoint) {
    String[] names = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
    Object[] args = joinPoint.getArgs();
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (args[i] instanceof ServletRequest || args[i] instanceof ServletResponse) {
        continue;
      }
      out.put(names != null ? names[i] : "arg" + i, args[i]);
    }
    return out;
  }
}
