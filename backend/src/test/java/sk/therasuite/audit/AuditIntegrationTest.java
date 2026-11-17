package sk.therasuite.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.audit.domain.AuditViolationException;
import sk.therasuite.audit.infrastructure.AuditPartitionInitializer;
import sk.therasuite.iam.infrastructure.UserAccount;
import sk.therasuite.iam.infrastructure.UserAccountRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AuditIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired UserAccountRepository users;
  @Autowired TransactionTemplate tx;
  @Autowired AuditPartitionInitializer partitions;

  private RequestPostProcessor as(String username, String... roles) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, roles);
    }
    return TestUsers.as(jdbc, username);
  }

  private MvcResult createUser(RequestPostProcessor actor, String username) throws Exception {
    return mvc.perform(
            post("/api/admin/users")
                .with(csrf())
                .with(actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"%s\",\"displayName\":\"Audit Test\",\"roles\":[{\"roleCode\":\"DOCTOR\"}]}"
                        .formatted(username)))
        .andReturn();
  }

  @Test
  void partitionsExistForCurrentAndNextTwoMonths() {
    YearMonth now = YearMonth.now(ZoneOffset.UTC);
    for (String table : List.of("audit_event", "audit_change")) {
      for (int i = 0; i <= 2; i++) {
        YearMonth m = now.plusMonths(i);
        String name = "%s_y%04dm%02d".formatted(table, m.getYear(), m.getMonthValue());
        assertThat(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, name))
            .as(name)
            .isTrue();
      }
    }
    partitions.ensurePartitions(now); // opakované volanie nič nerozbije
  }

  @Test
  void writeCreatesAuditEventWithChangeAndHeaderAndNoPassword() throws Exception {
    MvcResult result = createUser(as("audit.admin", "ADMIN"), "audit.created");

    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    String eventId = result.getResponse().getHeader("X-Audit-Event-Id");
    assertThat(eventId).isNotBlank();

    Map<String, Object> event =
        jdbc.queryForMap("select * from audit_event where id = ?", Long.valueOf(eventId));
    assertThat(event)
        .containsEntry("event_type", "WRITE")
        .containsEntry("outcome", "SUCCESS")
        .containsEntry("operation", "USER_CREATE")
        .containsEntry("username", "audit.admin")
        .containsEntry("roles", "ADMIN");
    assertThat(event.get("request_id")).isNotNull();
    assertThat(event.get("request_payload").toString()).contains("audit.created");

    Map<String, Object> change =
        jdbc.queryForMap(
            "select entity_type, change_type, after_state::text a, changed_fields::text c"
                + " from audit_change where audit_event_id = ? and entity_type = 'user_account'",
            Long.valueOf(eventId));
    assertThat(change).containsEntry("change_type", "CREATE");
    assertThat(change.get("a").toString()).contains("audit.created");

    String oneTimePassword =
        com.jayway.jsonpath.JsonPath.read(
            result.getResponse().getContentAsString(), "$.oneTimePassword");
    List<String> everything =
        jdbc.queryForList(
            "select e::text from audit_event e union all select c::text from audit_change c",
            String.class);
    assertThat(everything).isNotEmpty();
    assertThat(String.join("\n", everything))
        .doesNotContain(oneTimePassword)
        .doesNotContain("$argon2");
    assertThat(change.get("a").toString()).doesNotContain("passwordHash");
  }

  @Test
  void rejectedWriteCreatesRejectedEventAndRollsBack() throws Exception {
    RequestPostProcessor admin = as("audit.admin", "ADMIN");
    createUser(admin, "audit.dup");
    MvcResult second = createUser(admin, "audit.dup");

    assertThat(second.getResponse().getStatus()).isEqualTo(409);
    assertThat(second.getResponse().getHeader("X-Audit-Event-Id")).isNull();
    Map<String, Object> rejected =
        jdbc.queryForMap(
            "select outcome, error_code, operation from audit_event"
                + " where outcome = 'REJECTED' and request_payload::text like '%audit.dup%'");
    assertThat(rejected)
        .containsEntry("outcome", "REJECTED")
        .containsEntry("error_code", "CONFLICT")
        .containsEntry("operation", "USER_CREATE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_account where username = 'audit.dup'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void entityChangeOutsideAuditedOperationFailsAndRollsBack() {
    assertThatThrownBy(() ->
            tx.executeWithoutResult(
                s -> users.saveAndFlush(new UserAccount("bez.auditu", "Bez auditu"))))
        .isInstanceOf(AuditViolationException.class);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_account where username = 'bez.auditu'", Long.class))
        .isZero();
  }

  @Test
  void auditTablesRejectUpdateAndDelete() throws Exception {
    createUser(as("audit.admin", "ADMIN"), "audit.append");

    for (String sql :
        List.of(
            "update audit_event set operation = 'X'",
            "delete from audit_event",
            "update audit_change set entity_type = 'x'",
            "delete from audit_change")) {
      assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void loginSuccessFailureAndLockAreAuditedAsLogin() throws Exception {
    TestUsers.create(jdbc, encoder, "audit.login", PASSWORD, false, "RECEPTIONIST");
    login("audit.login", PASSWORD);
    for (int i = 0; i < 5; i++) {
      login("audit.login", "zle-heslo-123");
    }
    login("audit.unknown", "zle-heslo-123");

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select operation, outcome, error_code from audit_event"
                + " where event_type = 'LOGIN' and username = 'audit.login' order by id");
    assertThat(rows).hasSize(6);
    assertThat(rows.get(0)).containsEntry("operation", "LOGIN").containsEntry("outcome", "SUCCESS");
    assertThat(rows.get(1))
        .containsEntry("outcome", "REJECTED")
        .containsEntry("error_code", "INVALID_CREDENTIALS");
    assertThat(rows.get(5))
        .containsEntry("operation", "ACCOUNT_LOCK")
        .containsEntry("error_code", "ACCOUNT_LOCKED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where event_type = 'LOGIN'"
                    + " and username = 'audit.unknown' and user_id is null and outcome = 'REJECTED'",
                Long.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_change c join audit_event e on e.id = c.audit_event_id"
                    + " where e.username = 'audit.login' and c.entity_type = 'user_account'",
                Long.class))
        .isEqualTo(6);
  }

  private void login(String username, String password) throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
        .andReturn();
  }

  @Test
  void auditEndpointsRequireAuditViewAndSupportFiltersAndDetail() throws Exception {
    MvcResult created = createUser(as("audit.admin", "ADMIN"), "audit.listed");
    String eventId = created.getResponse().getHeader("X-Audit-Event-Id");
    RequestPostProcessor auditor = as("audit.auditor", "AUDITOR");

    mvc.perform(get("/api/audit/events").with(as("audit.reception", "RECEPTIONIST")))
        .andExpect(status().isForbidden());

    mvc.perform(
            get("/api/audit/events")
                .with(auditor)
                .param("operation", "USER_CREATE")
                .param("type", "WRITE")
                .param("entityType", "user_account")
                .param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(5))
        .andExpect(jsonPath("$.content[*].id", hasItem(Integer.valueOf(eventId))));

    mvc.perform(get("/api/audit/events").with(auditor).param("operation", "NEEXISTUJE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));

    mvc.perform(get("/api/audit/events/" + eventId).with(auditor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.event.operation").value("USER_CREATE"))
        .andExpect(jsonPath("$.requestPayload.username").value("audit.listed"))
        .andExpect(jsonPath("$.changes[0].changeType").value("CREATE"))
        .andExpect(jsonPath("$.changes[0].afterState.username").value("audit.listed"))
        .andExpect(header().doesNotExist("X-Audit-Event-Id"));

    mvc.perform(get("/api/audit/events/999999999").with(auditor)).andExpect(status().isNotFound());
  }
}
