package sk.therasuite.iam.application;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.audit.application.AuditEventSpec;
import sk.therasuite.audit.application.AuditEventSpec.Actor;
import sk.therasuite.audit.application.AuditService;
import sk.therasuite.audit.domain.AuditOutcome;
import sk.therasuite.iam.domain.UserStatus;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.application.ApiException;

/** Overenie hesla, zamykanie účtov a zmena vlastného hesla. */
@Service
public class AuthService {

  public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
  public static final String ACCOUNT_LOCKED = "ACCOUNT_LOCKED";
  public static final int MIN_PASSWORD_LENGTH = 10;

  private final UserAccountRepository users;
  private final PasswordEncoder encoder;
  private final IamProperties properties;
  private final AuditService audit;
  private volatile String dummyHash;

  public AuthService(
      UserAccountRepository users,
      PasswordEncoder encoder,
      IamProperties properties,
      AuditService audit) {
    this.users = users;
    this.audit = audit;
    this.encoder = encoder;
    this.properties = properties;
  }

  /**
   * Overí prihlasovacie údaje a zapíše audit záznam typu LOGIN (úspech, neúspech, zamknutie účtu).
   * Výsledok je známy pred zápisom, lebo audit je len na pridávanie; zmena stavu účtu (počítadlo
   * pokusov, zamknutie) prebehne v transakcii auditu. Po {@code security.login.maxFailedAttempts}
   * pokusoch sa účet zamkne.
   */
  public AuthenticatedUser login(String username, String password) {
    Instant now = Instant.now();
    UserAccount user = users.findByUsername(username).orElse(null);
    if (user == null
        || user.getPasswordHash() == null
        || user.getStatus() == UserStatus.DISABLED
        || !"LOCAL".equals(user.getAuthProvider())) {
      encoder.matches(password, dummyHash());
      audit.run(
          AuditEventSpec.login(
              "LOGIN",
              new Actor(user == null ? null : user.getId(), username),
              AuditOutcome.REJECTED,
              INVALID_CREDENTIALS),
          () -> {});
      throw invalid();
    }
    boolean locked = user.getStatus() == UserStatus.LOCKED && !user.lockExpired(now);
    boolean matches = encoder.matches(password, user.getPasswordHash());
    Actor actor = new Actor(user.getId(), user.getUsername());
    if (locked) {
      audit.run(
          AuditEventSpec.login(
              "LOGIN",
              actor,
              AuditOutcome.REJECTED,
              matches ? ACCOUNT_LOCKED : INVALID_CREDENTIALS),
          () -> unlockIfExpired(user.getId(), now));
      throw matches
          ? new ApiException(HttpStatus.LOCKED, ACCOUNT_LOCKED, "Účet je zamknutý.")
          : invalid();
    }
    if (!matches) {
      boolean locksNow = user.getFailedLoginCount() + 1 >= properties.login().maxFailedAttempts();
      audit.run(
          AuditEventSpec.login(
              locksNow ? "ACCOUNT_LOCK" : "LOGIN",
              actor,
              AuditOutcome.REJECTED,
              locksNow ? ACCOUNT_LOCKED : INVALID_CREDENTIALS),
          () -> {
            UserAccount u = unlockIfExpired(user.getId(), now);
            u.registerFailedLogin(
                properties.login().maxFailedAttempts(), properties.login().lockDuration(), now);
          });
      throw invalid();
    }
    audit.run(
        AuditEventSpec.login("LOGIN", actor, AuditOutcome.SUCCESS, null),
        () -> unlockIfExpired(user.getId(), now).registerSuccessfulLogin(now));
    return new AuthenticatedUser(user.getId(), user.getUsername());
  }

  private UserAccount unlockIfExpired(long userId, Instant now) {
    UserAccount u = users.findById(userId).orElseThrow();
    if (u.lockExpired(now)) {
      u.unlock();
    }
    return u;
  }

  @AuditedOperation(operation = "PASSWORD_CHANGE", useCase = "IAM")
  @Transactional
  public void changePassword(long userId, String currentPassword, String newPassword) {
    UserAccount user = users.findById(userId).orElseThrow();
    if (user.getPasswordHash() == null
        || !encoder.matches(currentPassword, user.getPasswordHash())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "Súčasné heslo nie je správne.");
    }
    if (newPassword.equals(currentPassword)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "Nové heslo musí byť iné ako súčasné.");
    }
    user.changePassword(encoder.encode(newPassword), false, Instant.now());
  }

  private ApiException invalid() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "Nesprávne meno alebo heslo.");
  }

  private String dummyHash() {
    String h = dummyHash;
    if (h == null) {
      h = dummyHash = encoder.encode("neexistujuci-pouzivatel");
    }
    return h;
  }
}
