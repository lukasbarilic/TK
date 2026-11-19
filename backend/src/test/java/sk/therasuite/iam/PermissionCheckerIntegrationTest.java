package sk.therasuite.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.shared.AuthenticatedUser;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PermissionCheckerIntegrationTest {

  @Autowired PermissionChecker checker;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private long centreA;
  private long centreB;
  private long receptionA;
  private long receptionB;

  private static final AtomicInteger SEQ = new AtomicInteger();
  private String n;

  @BeforeEach
  void data() {
    n = String.valueOf(SEQ.incrementAndGet());
    centreA = insert("insert into centre (code, name) values ('PC-A" + n + "', 'A') returning id");
    centreB = insert("insert into centre (code, name) values ('PC-B" + n + "', 'B') returning id");
    receptionA =
        insert("insert into reception (code, name) values ('PR-A" + n + "', 'A') returning id");
    receptionB =
        insert("insert into reception (code, name) values ('PR-B" + n + "', 'B') returning id");
    jdbc.update(
        "insert into building (centre_id, reception_id, code, name) values (?, ?, 'B1', 'B1')",
        centreA,
        receptionA);
    jdbc.update(
        "insert into building (centre_id, reception_id, code, name) values (?, ?, 'B2', 'B2')",
        centreB,
        receptionB);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  private long insert(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private long user(String name, String... roles) {
    long id = TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false, roles);
    SecurityContextHolder.getContext()
        .setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedUser(id, name), null, List.of()));
    return id;
  }

  private long customRole(String code, int limit) {
    long roleId =
        insert(
            "insert into role (code, name) values ('" + code + "', '" + code + "') returning id");
    jdbc.update(
        "insert into role_permission (role_id, permission_code, limit_value)"
            + " values (?, 'STAY_CREATE', ?)",
        roleId,
        limit);
    return roleId;
  }

  @Test
  void requireChecksPermissionsNotRoleNames() {
    user("pc.doctor" + n + n, "DOCTOR");

    assertThatCode(() -> checker.require("GUEST_LOCATION_VIEW")).doesNotThrowAnyException();
    assertThatThrownBy(() -> checker.require("PLAN_VIEW"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void roleScopedToReceptionAppliesOnlyThere() {
    long id = user("pc.scoped" + n);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id)"
            + " select ?, id, ? from role where code = 'RECEPTIONIST'",
        id,
        receptionA);

    assertThatCode(() -> checker.require("STAY_MOVE", receptionA)).doesNotThrowAnyException();
    assertThatThrownBy(() -> checker.require("STAY_MOVE", receptionB))
        .isInstanceOf(AccessDeniedException.class);
    assertThatCode(() -> checker.require("STAY_MOVE")).doesNotThrowAnyException();
  }

  @Test
  void roleScopedToCentreAppliesToReceptionsOfThatCentre() {
    long id = user("pc.centre" + n);
    jdbc.update(
        "insert into user_role (user_id, role_id, centre_id)"
            + " select ?, id, ? from role where code = 'RECEPTIONIST'",
        id,
        centreA);

    assertThatCode(() -> checker.require("STAY_MOVE", receptionA)).doesNotThrowAnyException();
    assertThatThrownBy(() -> checker.require("STAY_MOVE", receptionB))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void unscopedRoleAppliesToEveryReception() {
    user("pc.global" + n, "RECEPTIONIST");

    assertThatCode(() -> checker.require("STAY_MOVE", receptionB)).doesNotThrowAnyException();
  }

  @Test
  void limitIsHighestAcrossAllValidRoles() {
    long id = user("pc.limit" + n);
    long r30 = customRole("LIM30" + n, 30);
    long r90 = customRole("LIM90" + n, 90);
    jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", id, r30);
    assertThat(checker.limit("STAY_CREATE")).isEqualTo(30);

    jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", id, r90);
    assertThat(checker.limit("STAY_CREATE")).isEqualTo(90);

    jdbc.update(
        "insert into user_role (user_id, role_id) select ?, id from role where code = 'RECEPTIONIST'",
        id);
    assertThat(checker.limit("STAY_CREATE")).isEqualTo(365);
  }

  @Test
  void limitWithoutPermissionIsDenied() {
    user("pc.nolimit" + n, "AUDITOR");

    assertThatThrownBy(() -> checker.limit("STAY_CREATE"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void expiredAndFutureAssignmentsAreIgnored() {
    long id = user("pc.valid" + n);
    jdbc.update(
        "insert into user_role (user_id, role_id, valid_to)"
            + " select ?, id, current_date - 1 from role where code = 'RECEPTIONIST'",
        id);
    assertThatThrownBy(() -> checker.require("STAY_MOVE"))
        .isInstanceOf(AccessDeniedException.class);

    jdbc.update(
        "insert into user_role (user_id, role_id, valid_from, valid_to)"
            + " select ?, id, current_date, current_date + 1 from role where code = 'RECEPTIONIST'",
        id);
    assertThatCode(() -> checker.require("STAY_MOVE")).doesNotThrowAnyException();
  }

  @Test
  void disabledUserHasNoPermissions() {
    long id = user("pc.off" + n, "RECEPTIONIST");
    jdbc.update("update user_account set status = 'DISABLED' where id = ?", id);

    assertThatThrownBy(() -> checker.require("STAY_MOVE"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void seedMatchesDesign() {
    assertThat(jdbc.queryForObject("select count(*) from permission", Integer.class)).isEqualTo(26);
    assertThat(
            jdbc.queryForList(
                "select code from role where system_role order by code", String.class))
        .containsExactly(
            "ADMIN", "AUDITOR", "DOCTOR", "RECEPTIONIST", "RECEPTION_MANAGER", "SYSTEM");
    assertThat(
            jdbc.queryForObject(
                "select limit_value from role_permission rp join role r on r.id = rp.role_id"
                    + " where r.code = 'RECEPTIONIST' and rp.permission_code = 'STAY_CREATE'",
                Integer.class))
        .isEqualTo(365);
    assertThat(
            jdbc.queryForObject(
                "select password_hash from user_account where username = 'system'", String.class))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.table_constraints"
                    + " where constraint_name = 'centre_created_by_fk'",
                Integer.class))
        .isEqualTo(1);
  }
}
