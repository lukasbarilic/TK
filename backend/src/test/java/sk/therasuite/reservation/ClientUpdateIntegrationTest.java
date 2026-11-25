package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.notification.application.LiveConnection;
import sk.therasuite.notification.application.LiveConnections;
import sk.therasuite.notification.application.LiveEvent;

/** Oprava údajov klienta z detailu karty: PUT /api/clients/{id}. Fiktívne dáta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ClientUpdateIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired LiveConnections connections;

  /** Klient na dvoch kartách: {@code card} s pobytom a {@code otherCard} odhlásená, bez pobytu. */
  private record Fx(long reception, long card, long otherCard, long client, long watcherUser) {}

  private static final class Recorder implements LiveConnection {
    final List<LiveEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void send(LiveEvent event) throws IOException {
      if (!(event instanceof LiveEvent.Heartbeat)) {
        events.add(event);
      }
    }

    @Override
    public boolean isSessionValid() {
      return true;
    }

    @Override
    public void close() {}
  }

  private long id(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private Fx fx() {
    int n = ++seq;
    long centre = id("insert into centre (code, name) values ('CU" + n + "', 'Test') returning id");
    long reception =
        id("insert into reception (code, name) values ('CUR" + n + "', 'Test') returning id");
    long building =
        id(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            centre,
            reception);
    long base = id("insert into base_category (name) values ('CUK" + n + "') returning id");
    long category =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'K', 'Kat', 2) returning id",
            centre,
            base);
    long room =
        id(
            "insert into room (building_id, number, category_id, regular_beds, extra_beds)"
                + " values (?, '1', ?, 2, 0) returning id",
            building,
            category);
    jdbc.update("insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR')", room);
    long bed = id("select id from bed where room_id = ?", room);
    long payer =
        jdbc.queryForList("select id from payer limit 1", Long.class).stream()
            .findFirst()
            .orElseGet(() ->
                id(
                    "insert into payer (code, name, type) values ('CUP', 'Platca', 'SELF')"
                        + " returning id"));
    long card = card("RESERVED", category, payer);
    long otherCard = card("CHECKED_OUT", category, payer);
    long client =
        id(
            "insert into client (first_name, last_name, birth_date, nationality, email, phone)"
                + " values ('Ukážka', 'Preklepová', '1970-01-01', 'SK', 'zly@example.invalid',"
                + " '0900 000 000') returning id");
    for (long c : List.of(card, otherCard)) {
      jdbc.update(
          "insert into reservation_guest (reservation_id, client_id, primary_guest)"
              + " values (?, ?, true)",
          c,
          client);
    }
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, '2030-01-10', '2030-01-17', ?)",
        card,
        bed,
        category);
    long watcher = TestUsers.create(jdbc, encoder, "cu.watcher" + n, PASSWORD, false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id)"
            + " select ?, id, ? from role where code = 'RECEPTIONIST'",
        watcher,
        reception);
    return new Fx(reception, card, otherCard, client, watcher);
  }

  private long card(String status, long category, long payer) {
    return id(
        "insert into reservation (reservation_number, status, arrival_date, departure_date,"
            + " booked_category_id, payer_id) values (?, ?, '2030-01-10',"
            + " '2030-01-17', ?, ?) returning id",
        "CU" + System.nanoTime(),
        status,
        category,
        payer);
  }

  private RequestPostProcessor user(String username, String... roles) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, roles);
    }
    return TestUsers.as(jdbc, username);
  }

  private RequestPostProcessor receptionist() {
    return user("cu.recepcna", "RECEPTIONIST");
  }

  /** Používateľ len s oprávnením RESERVATION_VIEW. */
  private RequestPostProcessor viewer() {
    if (jdbc.queryForObject("select count(*) from role where code = 'CU_VIEWER'", Long.class)
        == 0) {
      jdbc.update("insert into role (code, name) values ('CU_VIEWER', 'Len čítanie')");
      jdbc.update(
          "insert into role_permission (role_id, permission_code)"
              + " select r.id, p.code from role r, permission p"
              + " where r.code = 'CU_VIEWER' and p.code = 'RESERVATION_VIEW'");
    }
    return user("cu.citatel", "CU_VIEWER");
  }

  private long version(long client) {
    return id("select version from client where id = ?", client);
  }

  private MvcResult putClient(RequestPostProcessor who, long client, long version, String fields)
      throws Exception {
    return mvc.perform(
            put("/api/clients/" + client)
                .with(csrf())
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version + "," + fields + "}"))
        .andReturn();
  }

  private static final String VALID =
      "\"firstName\":\" Ukážka \",\"lastName\":\"Opravená\",\"birthDate\":\"1971-02-03\","
          + "\"nationality\":\"cz\",\"email\":\"dobry@example.invalid\",\"phone\":\"\"";

  private String body(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString();
  }

  private String getCard(long card) throws Exception {
    return body(mvc.perform(get("/api/reservations/" + card).with(receptionist())).andReturn());
  }

  @Test
  void correctionIsSavedAndShownOnAllCardsOfClient() throws Exception {
    Fx fx = fx();
    String before = getCard(fx.card());
    long clientVersion = JsonPath.<Integer>read(before, "$.guests[0].clientVersion");

    MvcResult r = putClient(receptionist(), fx.client(), clientVersion, VALID);

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(body(r), "$.firstName")).isEqualTo("Ukážka");
    assertThat(JsonPath.<String>read(body(r), "$.lastName")).isEqualTo("Opravená");
    for (long card : List.of(fx.card(), fx.otherCard())) {
      String json = getCard(card);
      assertThat(JsonPath.<String>read(json, "$.guests[0].lastName")).isEqualTo("Opravená");
      assertThat(JsonPath.<String>read(json, "$.guests[0].birthDate")).isEqualTo("1971-02-03");
      assertThat(JsonPath.<String>read(json, "$.guests[0].nationality")).isEqualTo("CZ");
      assertThat(JsonPath.<String>read(json, "$.guests[0].email"))
          .isEqualTo("dobry@example.invalid");
      assertThat((String) JsonPath.read(json, "$.guests[0].phone")).isNull();
      assertThat(JsonPath.<Integer>read(json, "$.guests[0].clientVersion").longValue())
          .isGreaterThan(clientVersion);
    }
  }

  @Test
  void invalidInputIsRejected() throws Exception {
    Fx fx = fx();
    long v = version(fx.client());

    assertThat(
            putClient(receptionist(), fx.client(), v, VALID.replace("Opravená", "  "))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            putClient(
                    receptionist(),
                    fx.client(),
                    v,
                    VALID.replace("dobry@example.invalid", "nie-je-email"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            putClient(receptionist(), fx.client(), v, VALID.replace("\"cz\"", "\"CZE\""))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(putClient(receptionist(), fx.client(), v + 5, VALID).getResponse().getStatus())
        .isEqualTo(409);
    assertThat(putClient(receptionist(), Long.MAX_VALUE, 0, VALID).getResponse().getStatus())
        .isEqualTo(404);
    assertThat(
            jdbc.queryForObject(
                "select last_name from client where id = ?", String.class, fx.client()))
        .isEqualTo("Preklepová");
  }

  @Test
  void requiresReservationEdit() throws Exception {
    Fx fx = fx();

    assertThat(
            putClient(viewer(), fx.client(), version(fx.client()), VALID)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
  }

  @Test
  void updateIsAuditedAndSendsPlanChanged() throws Exception {
    Fx fx = fx();
    Recorder recorder = new Recorder();
    connections.register(fx.watcherUser(), recorder);
    try {
      MvcResult r = putClient(receptionist(), fx.client(), version(fx.client()), VALID);

      assertThat(r.getResponse().getStatus()).isEqualTo(200);
      assertThat(
              jdbc.queryForObject(
                  "select operation || '/' || outcome from audit_event where id = ?",
                  String.class,
                  Long.parseLong(r.getResponse().getHeader("X-Audit-Event-Id"))))
          .isEqualTo("CLIENT_UPDATE/SUCCESS");
      assertThat(recorder.events).hasSize(1);
      assertThat(recorder.events).allMatch(e -> e instanceof LiveEvent.PlanChanged);
    } finally {
      connections.unregister(recorder);
    }
  }
}
