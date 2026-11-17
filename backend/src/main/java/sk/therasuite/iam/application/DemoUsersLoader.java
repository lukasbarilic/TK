package sk.therasuite.iam.application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import sk.therasuite.audit.application.AuditEventSpec;
import sk.therasuite.audit.application.AuditService;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;

/**
 * Demo používatelia z {@code demo/users.csv} (fiktívni, heslo bez vynútenej zmeny). Len v profile
 * {@code demo} a len ak ešte neexistuje žiadny iný účet než {@code system}.
 */
@Component
@Profile("demo")
@Order(20)
public class DemoUsersLoader implements ApplicationRunner {

  private final UserAccountRepository users;
  private final IamQueries queries;
  private final PasswordEncoder encoder;
  private final AuditService audit;

  public DemoUsersLoader(
      UserAccountRepository users,
      IamQueries queries,
      PasswordEncoder encoder,
      AuditService audit) {
    this.users = users;
    this.queries = queries;
    this.encoder = encoder;
    this.audit = audit;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (users.countByUsernameNot(UserAdminService.SYSTEM_USERNAME) > 0) {
      return;
    }
    audit.run(AuditEventSpec.system("DEMO_DATA_LOAD"), this::load);
  }

  private void load() {
    boolean header = true;
    for (String line : readLines()) {
      if (header) {
        header = false;
        continue;
      }
      // username,display_name,roles,reception_code,password
      String[] c = line.split(",", -1);
      UserAccount user = new UserAccount(c[0], c[1]);
      user.changePassword(encoder.encode(c[4]), false, Instant.now());
      Long receptionId = c[3].isEmpty() ? null : queries.receptionIdByCode(c[3]).orElse(null);
      user.setDefaultReceptionId(receptionId);
      user = users.saveAndFlush(user);
      for (String role : c[2].split("\\|")) {
        queries.addAssignment(
            user.getId(), queries.roleId(role).orElseThrow(), null, receptionId, null, null);
      }
    }
  }

  private java.util.List<String> readLines() {
    ClassPathResource resource = new ClassPathResource("demo/users.csv");
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
      return reader.lines().filter(l -> !l.isBlank()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("Nepodarilo sa načítať demo/users.csv", e);
    }
  }
}
