package sk.therasuite.iam.infrastructure;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import sk.therasuite.iam.domain.UserStatus;
import sk.therasuite.shared.BaseEntity;

@Entity
@Table(name = "user_account")
public class UserAccount extends BaseEntity {

  private String username;
  private String displayName;
  private String email;

  @Enumerated(EnumType.STRING)
  private UserStatus status = UserStatus.ACTIVE;

  private String authProvider = "LOCAL";
  private String passwordHash;
  private Instant lastLoginAt;
  private String language;
  private boolean mustChangePassword;
  private int failedLoginCount;
  private Instant lockedUntil;
  private Instant passwordChangedAt;
  private Long defaultReceptionId;

  protected UserAccount() {}

  public UserAccount(String username, String displayName) {
    this.username = username;
    this.displayName = displayName;
  }

  /** Nastaví nové heslo (hash). {@code mustChange} vynúti zmenu pri ďalšom prihlásení. */
  public void changePassword(String hash, boolean mustChange, Instant now) {
    this.passwordHash = hash;
    this.mustChangePassword = mustChange;
    this.passwordChangedAt = now;
  }

  public void registerFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
    failedLoginCount++;
    if (failedLoginCount >= maxAttempts) {
      status = UserStatus.LOCKED;
      lockedUntil = now.plus(lockDuration);
    }
  }

  public void registerSuccessfulLogin(Instant now) {
    failedLoginCount = 0;
    lastLoginAt = now;
  }

  public void unlock() {
    if (status == UserStatus.LOCKED) {
      status = UserStatus.ACTIVE;
    }
    failedLoginCount = 0;
    lockedUntil = null;
  }

  public boolean lockExpired(Instant now) {
    return status == UserStatus.LOCKED && lockedUntil != null && !lockedUntil.isAfter(now);
  }

  public void setStatus(UserStatus status) {
    this.status = status;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public void setLanguage(String language) {
    this.language = language;
  }

  public void setDefaultReceptionId(Long defaultReceptionId) {
    this.defaultReceptionId = defaultReceptionId;
  }

  public String getUsername() {
    return username;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getEmail() {
    return email;
  }

  public UserStatus getStatus() {
    return status;
  }

  public String getAuthProvider() {
    return authProvider;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public String getLanguage() {
    return language;
  }

  public boolean isMustChangePassword() {
    return mustChangePassword;
  }

  public int getFailedLoginCount() {
    return failedLoginCount;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public Instant getPasswordChangedAt() {
    return passwordChangedAt;
  }

  public Long getDefaultReceptionId() {
    return defaultReceptionId;
  }
}
