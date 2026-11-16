package sk.therasuite.iam.application;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.iam.domain.UserStatus;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.IamQueries.Assignment;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;
import sk.therasuite.shared.application.ApiException;

/** Správa používateľov (oprávnenie USER_ADMIN, kontroluje ho volajúci controller). */
@Service
public class UserAdminService {

  public static final String SYSTEM_USERNAME = "system";
  private static final String SYSTEM_ROLE = "SYSTEM";
  private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Pridelenie roly v požiadavke. */
  public record RoleAssignmentInput(
      String roleCode, Long centreId, Long receptionId, LocalDate validFrom, LocalDate validTo) {}

  public record UserView(
      Long id,
      String username,
      String displayName,
      String email,
      UserStatus status,
      String language,
      boolean mustChangePassword,
      Instant lockedUntil,
      Instant lastLoginAt,
      Long defaultReceptionId,
      List<Assignment> roles) {}

  /** Založený používateľ a jednorazové heslo (zobrazí sa len raz). */
  public record CreatedUser(UserView user, String oneTimePassword) {}

  private final UserAccountRepository users;
  private final IamQueries queries;
  private final PasswordEncoder encoder;

  public UserAdminService(
      UserAccountRepository users, IamQueries queries, PasswordEncoder encoder) {
    this.users = users;
    this.queries = queries;
    this.encoder = encoder;
  }

  @Transactional(readOnly = true)
  public List<UserView> list() {
    return users.findAllByOrderByUsernameAsc().stream().map(this::view).toList();
  }

  @Transactional
  public CreatedUser create(
      String username,
      String displayName,
      String email,
      String language,
      Long defaultReceptionId,
      List<RoleAssignmentInput> roles) {
    if (users.existsByUsername(username)) {
      throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "Používateľ už existuje.");
    }
    checkReception(defaultReceptionId);
    UserAccount user = new UserAccount(username, displayName);
    user.setEmail(email);
    user.setLanguage(language);
    user.setDefaultReceptionId(defaultReceptionId);
    String password = generatePassword();
    user.changePassword(encoder.encode(password), true, Instant.now());
    user = users.saveAndFlush(user);
    if (roles != null) {
      applyRoles(user.getId(), roles);
    }
    return new CreatedUser(view(user), password);
  }

  @Transactional
  public UserView update(
      long id,
      String displayName,
      String email,
      String language,
      UserStatus status,
      Long defaultReceptionId) {
    UserAccount user = editable(id);
    if (status == UserStatus.LOCKED) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Účet sa zamyká len neúspešnými prihláseniami.");
    }
    checkReception(defaultReceptionId);
    if (displayName != null) {
      user.setDisplayName(displayName);
    }
    if (email != null) {
      user.setEmail(email);
    }
    if (language != null) {
      user.setLanguage(language);
    }
    if (defaultReceptionId != null) {
      user.setDefaultReceptionId(defaultReceptionId);
    }
    if (status == UserStatus.DISABLED) {
      user.setStatus(UserStatus.DISABLED);
    } else if (status == UserStatus.ACTIVE) {
      user.setStatus(UserStatus.ACTIVE);
      user.unlock();
    }
    return view(users.saveAndFlush(user));
  }

  @Transactional
  public UserView replaceRoles(long id, List<RoleAssignmentInput> roles) {
    UserAccount user = editable(id);
    applyRoles(user.getId(), roles);
    return view(user);
  }

  @Transactional
  public String resetPassword(long id) {
    UserAccount user = editable(id);
    String password = generatePassword();
    user.changePassword(encoder.encode(password), true, Instant.now());
    users.saveAndFlush(user);
    return password;
  }

  @Transactional
  public UserView unlock(long id) {
    UserAccount user = editable(id);
    user.unlock();
    return view(users.saveAndFlush(user));
  }

  private void applyRoles(long userId, List<RoleAssignmentInput> roles) {
    for (RoleAssignmentInput r : roles) {
      if (SYSTEM_ROLE.equals(r.roleCode())) {
        throw badRequest("Rolu SYSTEM nie je možné prideliť.");
      }
      if (r.validFrom() != null && r.validTo() != null && r.validFrom().isAfter(r.validTo())) {
        throw badRequest("valid_from musí byť pred valid_to.");
      }
      if (r.centreId() != null && !queries.centreExists(r.centreId())) {
        throw badRequest("Stredisko " + r.centreId() + " neexistuje.");
      }
      checkReception(r.receptionId());
    }
    List<Long> roleIds =
        roles.stream()
            .map(r ->
                queries
                    .roleId(r.roleCode())
                    .orElseThrow(() -> badRequest("Rola " + r.roleCode() + " neexistuje.")))
            .toList();
    queries.clearAssignments(userId);
    for (int i = 0; i < roles.size(); i++) {
      RoleAssignmentInput r = roles.get(i);
      queries.addAssignment(
          userId, roleIds.get(i), r.centreId(), r.receptionId(), r.validFrom(), r.validTo());
    }
  }

  private UserAccount editable(long id) {
    UserAccount user =
        users
            .findById(id)
            .orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Používateľ neexistuje."));
    if (SYSTEM_USERNAME.equals(user.getUsername())) {
      throw badRequest("Technického používateľa system nie je možné meniť.");
    }
    return user;
  }

  private void checkReception(Long receptionId) {
    if (receptionId != null && !queries.receptionExists(receptionId)) {
      throw badRequest("Recepcia " + receptionId + " neexistuje.");
    }
  }

  private UserView view(UserAccount u) {
    return new UserView(
        u.getId(),
        u.getUsername(),
        u.getDisplayName(),
        u.getEmail(),
        u.getStatus(),
        u.getLanguage(),
        u.isMustChangePassword(),
        u.getLockedUntil(),
        u.getLastLoginAt(),
        u.getDefaultReceptionId(),
        queries.assignments(u.getId()));
  }

  private static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
  }

  /** Náhodné jednorazové heslo (16 znakov, bez zameniteľných znakov). */
  public static String generatePassword() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 16; i++) {
      sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }
}
