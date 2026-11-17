package sk.therasuite.iam.application;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import sk.therasuite.audit.application.AuditEventSpec;
import sk.therasuite.audit.application.AuditService;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;

/**
 * Pri prvom štarte (okrem technického používateľa žiadny účet) založí administrátora. Heslo berie z
 * {@code APP_BOOTSTRAP_ADMIN_PASSWORD}; ak chýba, vygeneruje jednorazové a vypíše ho raz do logu.
 */
@Component
@Order(30)
public class BootstrapAdminRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

  private final UserAccountRepository users;
  private final IamQueries queries;
  private final PasswordEncoder encoder;
  private final BootstrapProperties properties;
  private final AuditService audit;

  public BootstrapAdminRunner(
      UserAccountRepository users,
      IamQueries queries,
      PasswordEncoder encoder,
      BootstrapProperties properties,
      AuditService audit) {
    this.users = users;
    this.queries = queries;
    this.encoder = encoder;
    this.properties = properties;
    this.audit = audit;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (users.countByUsernameNot(UserAdminService.SYSTEM_USERNAME) > 0) {
      return;
    }
    String username = blank(properties.username()) ? "admin" : properties.username().trim();
    boolean generated = blank(properties.password());
    String password = generated ? UserAdminService.generatePassword() : properties.password();
    audit.run(
        AuditEventSpec.system("BOOTSTRAP_ADMIN"),
        () -> {
          UserAccount admin = new UserAccount(username, "Administrátor");
          admin.changePassword(encoder.encode(password), true, Instant.now());
          admin = users.saveAndFlush(admin);
          queries.addAssignment(
              admin.getId(), queries.roleId("ADMIN").orElseThrow(), null, null, null, null);
        });
    if (generated) {
      log.warn(
          "Bootstrap: vytvorený administrátor '{}' s jednorazovým heslom: {} (zobrazené len raz,"
              + " pri prvom prihlásení sa vyžaduje zmena).",
          username,
          password);
    } else {
      log.info("Bootstrap: vytvorený administrátor '{}' (heslo z prostredia).", username);
    }
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }
}
