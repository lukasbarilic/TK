package sk.therasuite.iam.application;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.iam.domain.UserStatus;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;
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
  private volatile String dummyHash;

  public AuthService(
      UserAccountRepository users, PasswordEncoder encoder, IamProperties properties) {
    this.users = users;
    this.encoder = encoder;
    this.properties = properties;
  }

  /**
   * Overí prihlasovacie údaje. Neúspešné pokusy sa zapisujú aj pri výnimke (noRollbackFor); po
   * {@code security.login.maxFailedAttempts} pokusoch sa účet zamkne.
   */
  @Transactional(noRollbackFor = ApiException.class)
  public AuthenticatedUser login(String username, String password) {
    Instant now = Instant.now();
    UserAccount user = users.findByUsername(username).orElse(null);
    if (user == null
        || user.getPasswordHash() == null
        || user.getStatus() == UserStatus.DISABLED
        || !"LOCAL".equals(user.getAuthProvider())) {
      encoder.matches(password, dummyHash());
      throw invalid();
    }
    if (user.lockExpired(now)) {
      user.unlock();
    }
    boolean matches = encoder.matches(password, user.getPasswordHash());
    if (user.getStatus() == UserStatus.LOCKED) {
      throw matches
          ? new ApiException(HttpStatus.LOCKED, ACCOUNT_LOCKED, "Účet je zamknutý.")
          : invalid();
    }
    if (!matches) {
      user.registerFailedLogin(
          properties.login().maxFailedAttempts(), properties.login().lockDuration(), now);
      throw invalid();
    }
    user.registerSuccessfulLogin(now);
    return new AuthenticatedUser(user.getId(), user.getUsername());
  }

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
