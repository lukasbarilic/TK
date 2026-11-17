package sk.therasuite.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.audit.infrastructure.AuditRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AuditReadIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @MockitoSpyBean AuditRepository auditRepository;

  @AfterEach
  void resetSpy() {
    reset(auditRepository);
  }

  private RequestPostProcessor as(String username, String role) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, role);
    }
    return TestUsers.as(jdbc, username);
  }

  private long seedCategory() {
    Long existing =
        jdbc.query(
            "select id from room_category where code = 'AR1'",
            rs -> rs.next() ? rs.getLong(1) : null);
    if (existing != null) {
      return existing;
    }
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values ('AR', 'Audit čítanie') returning id",
            Long.class);
    long base =
        jdbc.queryForObject(
            "insert into base_category (name) values ('Audit čítanie') returning id", Long.class);
    return jdbc.queryForObject(
        "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
            + " values (?, ?, 'AR1', 'Dvojlôžková audit', 2) returning id",
        Long.class,
        centre,
        base);
  }

  private long seedPayer() {
    Long existing =
        jdbc.query(
            "select id from payer where code = 'ARP'", rs -> rs.next() ? rs.getLong(1) : null);
    if (existing != null) {
      return existing;
    }
    return jdbc.queryForObject(
        "insert into payer (code, name, type) values ('ARP', 'Samoplatca audit', 'SELF')"
            + " returning id",
        Long.class);
  }

  private static String guest(String first, String last) {
    return "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"birthDate\":\"1970-01-01\",\"nationality\":\"SK\",\"email\":\"%s@example.invalid\",\"phone\":\"+000 000 000\"}"
        .formatted(first, last, first.toLowerCase());
  }

  /** Založí kartu s jedným hosťom a vráti jej ID a ID klienta. */
  private long[] createCard(String first, String last) throws Exception {
    String body =
        """
        {"arrivalDate":"2031-05-01","departureDate":"2031-05-11","bookedCategoryId":%d,
         "payerId":%d,"adults":1,"children":0,"exclusiveRoomUse":false,"guests":[%s]}
        """
            .formatted(seedCategory(), seedPayer(), guest(first, last));
    MvcResult r =
        mvc.perform(
                post("/api/reservations")
                    .with(csrf())
                    .with(as("ar.recepcna", "RECEPTIONIST"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    String json = r.getResponse().getContentAsString();
    return new long[] {
      ((Number) JsonPath.read(json, "$.id")).longValue(),
      ((Number) JsonPath.read(json, "$.guests[0].clientId")).longValue()
    };
  }

  private long readEvents() {
    return jdbc.queryForObject(
        "select count(*) from audit_event where event_type = 'READ'", Long.class);
  }

  @Test
  void listWritesOneReadEventWithAllClientsOnPage() throws Exception {
    long[] a = createCard("Alfa", "Listovský");
    long[] b = createCard("Beta", "Listovský");
    long before = readEvents();

    mvc.perform(
            get("/api/reservations")
                .param("q", "Listovský")
                .with(as("ar.recepcna", "RECEPTIONIST")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2));

    assertThat(readEvents()).isEqualTo(before + 1);
    Map<String, Object> event =
        jdbc.queryForMap(
            "select id, operation, username, outcome from audit_event"
                + " where event_type = 'READ' order by id desc limit 1");
    assertThat(event.get("operation")).isEqualTo("RESERVATION_VIEW");
    assertThat(event.get("username")).isEqualTo("ar.recepcna");
    List<Long> clients =
        jdbc.queryForList(
            "select client_id from audit_subject_access where audit_event_id = ? and data_scope = 'NAME'",
            Long.class,
            event.get("id"));
    assertThat(clients).containsExactlyInAnyOrder(a[1], b[1]);
  }

  @Test
  void detailWritesContactScope() throws Exception {
    long[] card = createCard("Gama", "Detailový");

    mvc.perform(get("/api/reservations/" + card[0]).with(as("ar.recepcna", "RECEPTIONIST")))
        .andExpect(status().isOk());

    List<String> scopes =
        jdbc.queryForList(
            "select data_scope from audit_subject_access where client_id = ?",
            String.class,
            card[1]);
    assertThat(scopes).containsExactly("CONTACT");
  }

  @Test
  void failedAuditWriteBlocksResponse() throws Exception {
    long[] card = createCard("Delta", "Zlyhaný");
    doThrow(new IllegalStateException("audit nedostupný"))
        .when(auditRepository)
        .insertSubjectAccess(any(), anyCollection(), anyString());
    long before = readEvents();

    assertThatThrownBy(() ->
            mvc.perform(
                get("/api/reservations/" + card[0]).with(as("ar.recepcna", "RECEPTIONIST"))))
        .hasRootCauseInstanceOf(IllegalStateException.class);

    assertThat(readEvents()).isEqualTo(before); // transakcia auditu sa zrušila
  }

  @Test
  void catalogReadIsNotAudited() throws Exception {
    long before = readEvents();
    mvc.perform(get("/api/catalog/centres").with(as("ar.recepcna", "RECEPTIONIST")));
    assertThat(readEvents()).isEqualTo(before);
  }

  @Test
  void clientAccessOverviewReturnsWhoAndWhen() throws Exception {
    long[] card = createCard("Epsilon", "Prehľadový");
    mvc.perform(get("/api/reservations/" + card[0]).with(as("ar.recepcna", "RECEPTIONIST")))
        .andExpect(status().isOk());

    mvc.perform(get("/api/audit/clients/" + card[1] + "/access").with(as("ar.audit", "AUDITOR")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].username").value("ar.recepcna"))
        .andExpect(jsonPath("$.content[0].operation").value("RESERVATION_VIEW"))
        .andExpect(jsonPath("$.content[0].dataScope").value("CONTACT"));

    mvc.perform(
            get("/api/audit/clients/" + card[1] + "/access")
                .param("from", "2000-01-01T00:00:00Z")
                .param("to", "2000-01-02T00:00:00Z")
                .with(as("ar.audit", "AUDITOR")))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void clientAccessOverviewRequiresAuditView() throws Exception {
    mvc.perform(get("/api/audit/clients/1/access").with(as("ar.recepcna", "RECEPTIONIST")))
        .andExpect(status().isForbidden());
  }

  @Test
  void subjectAccessIsAppendOnly() throws Exception {
    long[] card = createCard("Zeta", "Nemenný");
    mvc.perform(get("/api/reservations/" + card[0]).with(as("ar.recepcna", "RECEPTIONIST")));

    assertThatThrownBy(
            () -> jdbc.update("delete from audit_subject_access where client_id = ?", card[1]))
        .hasMessageContaining("len na pridávanie");
    assertThatThrownBy(() -> jdbc.update("update audit_subject_access set data_scope = 'FULL'"))
        .hasMessageContaining("len na pridávanie");
  }
}
