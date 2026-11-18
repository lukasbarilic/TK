package sk.therasuite.audit.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.audit.infrastructure.AuditContext;
import sk.therasuite.audit.infrastructure.AuditRepository;
import sk.therasuite.audit.infrastructure.AuditRepository.NewEvent;
import sk.therasuite.audit.infrastructure.AuditRequestInfo;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/** Prijatie dávky zobrazení z lokálnej kópie a ich zápis do auditu ako READ (HLD kap. 7.4). */
@Service
public class AuditOfflineReadService {

  public static final int MAX_BATCH = 500;
  static final Duration MAX_AGE = Duration.ofDays(365);
  static final Duration MAX_FUTURE = Duration.ofMinutes(10);

  /** Zdroj zobrazenia: bez spojenia, alebo odpoveď 304 z lokálnej kópie. */
  public enum Connection {
    OFFLINE,
    CACHED
  }

  public record OfflineRead(
      @NotNull UUID uid,
      @NotNull Instant occurredAt,
      @NotBlank @Size(max = 100) String username,
      @NotBlank @Size(max = 100) String deviceId,
      @NotBlank @Size(max = 100) String operation,
      @NotNull DataScope dataScope,
      @NotEmpty @Size(max = 10000) List<@NotNull Long> clientIds,
      @NotNull Connection connection) {}

  public record BatchRequest(@NotNull List<@Valid @NotNull OfflineRead> records) {}

  public record BatchResult(int accepted, int duplicate) {}

  private final AuditRepository repository;

  public AuditOfflineReadService(AuditRepository repository) {
    this.repository = repository;
  }

  /** Celá dávka v jednej transakcii: buď sa prijme, alebo (422) nezapíše nič. */
  @Transactional
  public BatchResult receive(List<OfflineRead> records) {
    if (records.size() > MAX_BATCH) {
      throw unprocessable("BATCH_TOO_LARGE", "Dávka môže mať najviac " + MAX_BATCH + " záznamov.");
    }
    AuthenticatedUser user = currentUser();
    Instant now = Instant.now();
    for (OfflineRead r : records) {
      if (!r.username().equals(user.username())) {
        throw unprocessable(
            "USERNAME_MISMATCH", "Záznam patrí inému používateľovi, než je prihlásený.");
      }
      if (r.occurredAt().isBefore(now.minus(MAX_AGE))
          || r.occurredAt().isAfter(now.plus(MAX_FUTURE))) {
        throw unprocessable("INVALID_OCCURRED_AT", "Čas zobrazenia je mimo povoleného rozsahu.");
      }
    }
    String roles = repository.rolesOf(user.id());
    AuditRequestInfo.Info request = AuditRequestInfo.current();
    int accepted = 0;
    for (OfflineRead r : records) {
      if (!repository.registerOfflineUid(r.uid())) {
        continue;
      }
      AuditContext.Current event =
          repository.insertOfflineReadEvent(
              r.uid(),
              new NewEvent(
                  "READ",
                  user.id(),
                  user.username(),
                  roles,
                  r.operation(),
                  null,
                  "SUCCESS",
                  null,
                  null,
                  request.requestId(),
                  request.clientIp(),
                  request.userAgent()),
              r.connection().name(),
              r.occurredAt(),
              r.deviceId());
      repository.insertSubjectAccess(event, r.clientIds(), r.dataScope().name());
      accepted++;
    }
    return new BatchResult(accepted, records.size() - accepted);
  }

  private static AuthenticatedUser currentUser() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Vyžaduje sa prihlásenie.");
  }

  private static ApiException unprocessable(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }
}
