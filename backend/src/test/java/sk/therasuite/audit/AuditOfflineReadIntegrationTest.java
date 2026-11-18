package sk.therasuite.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AuditOfflineReadIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static final String URL = "/api/audit/offline-reads";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private RequestPostProcessor as(String username, String role) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, role);
    }
    return TestUsers.as(jdbc, username);
  }

  private static String record(UUID uid, Instant at, String username, long clientId) {
    return """
    {"uid":"%s","occurredAt":"%s","username":"%s","deviceId":"zariadenie-1",
     "operation":"PLAN_VIEW","dataScope":"NAME","clientIds":[%d],"connection":"OFFLINE"}
    """
        .formatted(uid, at, username, clientId);
  }

  private static String batch(String... records) {
    return "{\"records\":[" + String.join(",", records) + "]}";
  }

  private long eventCount(UUID uid) {
    return jdbc.queryForObject(
        "select count(*) from audit_event where event_uid = ?", Long.class, uid);
  }

  @Test
  void storesReadEventAndShowsItInClientAccesses() throws Exception {
    UUID uid = UUID.randomUUID();
    Instant at = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
    long clientId = 910_001L;

    mvc.perform(
            post(URL)
                .with(csrf())
                .with(as("off.recepcna", "RECEPTIONIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(record(uid, at, "off.recepcna", clientId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(1))
        .andExpect(jsonPath("$.duplicate").value(0));

    var row =
        jdbc.queryForMap(
            "select event_type, operation, source, device_id, client_occurred_at, occurred_at"
                + " from audit_event where event_uid = ?",
            uid);
    assertThat(row.get("event_type")).isEqualTo("READ");
    assertThat(row.get("operation")).isEqualTo("PLAN_VIEW");
    assertThat(row.get("source")).isEqualTo("OFFLINE");
    assertThat(row.get("device_id")).isEqualTo("zariadenie-1");
    assertThat(((java.sql.Timestamp) row.get("client_occurred_at")).toInstant()).isEqualTo(at);
    assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant())
        .isAfter(at.plus(1, ChronoUnit.HOURS)); // čas servera, nie klienta

    mvc.perform(get("/api/audit/clients/" + clientId + "/access").with(as("off.audit", "AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].username").value("off.recepcna"))
        .andExpect(jsonPath("$.content[0].operation").value("PLAN_VIEW"))
        .andExpect(jsonPath("$.content[0].dataScope").value("NAME"))
        .andExpect(jsonPath("$.content[0].source").value("OFFLINE"));
  }

  @Test
  void resendingSameUidDoesNotCreateDuplicate() throws Exception {
    UUID uid = UUID.randomUUID();
    String body = batch(record(uid, Instant.now(), "off.recepcna", 910_002L));

    for (int expectedAccepted : new int[] {1, 0}) {
      mvc.perform(
              post(URL)
                  .with(csrf())
                  .with(as("off.recepcna", "RECEPTIONIST"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.accepted").value(expectedAccepted))
          .andExpect(jsonPath("$.duplicate").value(1 - expectedAccepted));
    }
    assertThat(eventCount(uid)).isEqualTo(1);
  }

  @Test
  void foreignUsernameRejectsWholeBatch() throws Exception {
    UUID ok = UUID.randomUUID();
    UUID foreign = UUID.randomUUID();
    mvc.perform(
            post(URL)
                .with(csrf())
                .with(as("off.recepcna", "RECEPTIONIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    batch(
                        record(ok, Instant.now(), "off.recepcna", 910_003L),
                        record(foreign, Instant.now(), "niekto.iny", 910_003L))))
        .andExpect(status().isUnprocessableEntity());
    assertThat(eventCount(ok)).isZero();
    assertThat(eventCount(foreign)).isZero();
  }

  @Test
  void occurredAtOutOfRangeIsRejected() throws Exception {
    for (Instant at :
        new Instant[] {
          Instant.now().minus(366, ChronoUnit.DAYS), Instant.now().plus(11, ChronoUnit.MINUTES)
        }) {
      mvc.perform(
              post(URL)
                  .with(csrf())
                  .with(as("off.recepcna", "RECEPTIONIST"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(batch(record(UUID.randomUUID(), at, "off.recepcna", 910_004L))))
          .andExpect(status().isUnprocessableEntity());
    }
  }

  @Test
  void batchLargerThan500IsRejected() throws Exception {
    String records =
        IntStream.range(0, 501)
            .mapToObj(i -> record(UUID.randomUUID(), Instant.now(), "off.recepcna", 910_005L))
            .collect(Collectors.joining(","));
    mvc.perform(
            post(URL)
                .with(csrf())
                .with(as("off.recepcna", "RECEPTIONIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(records)))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void batchOf500IsAccepted() throws Exception {
    String records =
        IntStream.range(0, 500)
            .mapToObj(i -> record(UUID.randomUUID(), Instant.now(), "off.recepcna", 910_006L))
            .collect(Collectors.joining(","));
    mvc.perform(
            post(URL)
                .with(csrf())
                .with(as("off.recepcna", "RECEPTIONIST"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(batch(records)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(500));
  }

  @Test
  void anonymousIsRejected() throws Exception {
    mvc.perform(post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(batch()))
        .andExpect(status().is4xxClientError());
  }
}
