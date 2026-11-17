package sk.therasuite.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.LocalDate;
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
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.notification.application.LiveConnection;
import sk.therasuite.notification.application.LiveConnections;
import sk.therasuite.notification.application.LiveEvent;

/** Živé udalosti plánu (TK-13). Obchodný deň recepcií: 2030-01-10. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class LiveEventsIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired LiveConnections connections;

  /** Zaznamenáva udalosti, ktoré by dostal prehliadač. */
  private static final class Recorder implements LiveConnection {
    final List<LiveEvent> events = new CopyOnWriteArrayList<>();
    volatile boolean valid = true;
    volatile boolean closed;

    @Override
    public void send(LiveEvent event) throws IOException {
      if (closed) {
        throw new IOException("odpojený");
      }
      if (!(event instanceof LiveEvent.Heartbeat)) {
        events.add(event);
      }
    }

    @Override
    public boolean isSessionValid() {
      return valid;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private record Fx(long reception, long room, long bed1, long bed2, long stay) {}

  private Fx fx() {
    return fx(true);
  }

  private Fx fx(boolean withStay) {
    int n = ++seq;
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values ('LV" + n + "', 'Test') returning id",
            Long.class);
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values ('LVR" + n + "', 'Test') returning id",
            Long.class);
    long building =
        jdbc.queryForObject(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            Long.class,
            centre,
            reception);
    long base =
        jdbc.queryForObject(
            "insert into base_category (name) values ('LVK" + n + "') returning id", Long.class);
    long category =
        jdbc.queryForObject(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'K', 'Kat', 2) returning id",
            Long.class,
            centre,
            base);
    jdbc.update(
        "insert into business_day (reception_id, business_date, status)"
            + " values (?, '2030-01-10', 'OPEN')",
        reception);
    long room =
        jdbc.queryForObject(
            "insert into room (building_id, number, category_id, regular_beds, extra_beds)"
                + " values (?, '1', ?, 2, 0) returning id",
            Long.class,
            building,
            category);
    for (int i = 1; i <= 2; i++) {
      jdbc.update("insert into bed (room_id, bed_number, type) values (?, ?, 'REGULAR')", room, i);
    }
    long bed1 = bed(room, 1);
    long bed2 = bed(room, 2);
    if (!withStay) {
      return new Fx(reception, room, bed1, bed2, 0);
    }
    long payer =
        jdbc.queryForList("select id from payer limit 1", Long.class).stream()
            .findFirst()
            .orElseGet(() ->
                jdbc.queryForObject(
                    "insert into payer (code, name, type) values ('LVP', 'Platca', 'SELF')"
                        + " returning id",
                    Long.class));
    long card =
        jdbc.queryForObject(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id) values (?, 'RESERVED', '2030-01-10',"
                + " '2030-01-17', ?, ?) returning id",
            Long.class,
            "LV" + System.nanoTime(),
            category,
            payer);
    long client =
        jdbc.queryForObject(
            "insert into client (first_name, last_name, birth_date)"
                + " values ('Ukážka', 'Hosťovská', '1970-01-01') returning id",
            Long.class);
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest)"
            + " values (?, ?, true)",
        card,
        client);
    long stay =
        jdbc.queryForObject(
            "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
                + " values (?, ?, '2030-01-10', '2030-01-17', ?) returning id",
            Long.class,
            card,
            bed1,
            category);
    return new Fx(reception, room, bed1, bed2, stay);
  }

  private long bed(long room, int number) {
    return jdbc.queryForObject(
        "select id from bed where room_id = ? and bed_number = ?", Long.class, room, number);
  }

  /** Používateľ s rolou obmedzenou na recepciu; vráti jeho id. */
  private long user(String username, String role, Long receptionScope) {
    long id = TestUsers.create(jdbc, encoder, username, PASSWORD, false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id) select ?, id, ? from role"
            + " where code = ?",
        id,
        receptionScope,
        role);
    return id;
  }

  private Recorder listen(long userId) {
    Recorder r = new Recorder();
    connections.register(userId, r);
    return r;
  }

  private MvcResult move(String actor, long stay, long bed) throws Exception {
    return mvc.perform(
            post("/api/stays/" + stay + "/move")
                .with(csrf())
                .with(TestUsers.as(jdbc, actor))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetBedId\":" + bed + "}"))
        .andReturn();
  }

  @Test
  void moveSendsPlanChangedToUserOfSameReceptionOnly() throws Exception {
    Fx a = fx();
    Fx b = fx();
    user("lv.actor1", "RECEPTIONIST", a.reception());
    long watcherA = user("lv.watcher1a", "RECEPTIONIST", a.reception());
    long watcherB = user("lv.watcher1b", "RECEPTIONIST", b.reception());
    Recorder ra = listen(watcherA);
    Recorder rb = listen(watcherB);
    try {
      MvcResult r = move("lv.actor1", a.stay(), a.bed2());

      assertThat(r.getResponse().getStatus()).isEqualTo(200);
      assertThat(ra.events).hasSize(1);
      LiveEvent.PlanChanged e = (LiveEvent.PlanChanged) ra.events.get(0);
      assertThat(e.receptionId()).isEqualTo(a.reception());
      assertThat(e.roomIds()).containsExactly(a.room());
      assertThat(e.from()).isEqualTo(LocalDate.of(2030, 1, 10));
      assertThat(e.to()).isEqualTo(LocalDate.of(2030, 1, 17));
      assertThat(e.auditEventId())
          .isEqualTo(Long.valueOf(r.getResponse().getHeader("X-Audit-Event-Id")));
      assertThat(rb.events).isEmpty();
    } finally {
      connections.unregister(ra);
      connections.unregister(rb);
    }
  }

  @Test
  void rejectedWriteSendsNothing() throws Exception {
    Fx a = fx();
    user("lv.actor2", "RECEPTIONIST", a.reception());
    long watcher = user("lv.watcher2", "RECEPTIONIST", a.reception());
    Recorder r = listen(watcher);
    try {
      // Presun na lôžko, kde už pobyt je (rovnaké lôžko) – zápis sa odmietne.
      MvcResult rejected = move("lv.actor2", a.stay(), a.bed1());
      assertThat(rejected.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
      assertThat(r.events).isEmpty();
    } finally {
      connections.unregister(r);
    }
  }

  @Test
  void eventContainsOnlyIdsAndDates() throws Exception {
    Fx a = fx();
    user("lv.actor3", "RECEPTIONIST", a.reception());
    long watcher = user("lv.watcher3", "RECEPTIONIST", a.reception());
    Recorder r = listen(watcher);
    try {
      move("lv.actor3", a.stay(), a.bed2());
      String json =
          new tools.jackson.databind.json.JsonMapper().writeValueAsString(r.events.get(0));
      assertThat(json).doesNotContain("Hosťovská").doesNotContain("Ukážka");
      assertThat(json)
          .containsOnlyOnce("receptionId")
          .contains("roomIds", "from", "to", "auditEventId");
    } finally {
      connections.unregister(r);
    }
  }

  @Test
  void closingBusinessDaySendsBusinessDayChanged() throws Exception {
    Fx a = fx(false);
    Fx b = fx(false);
    user("lv.actor4", "RECEPTIONIST", a.reception());
    long watcherA = user("lv.watcher4a", "RECEPTIONIST", a.reception());
    long watcherB = user("lv.watcher4b", "RECEPTIONIST", b.reception());
    Recorder ra = listen(watcherA);
    Recorder rb = listen(watcherB);
    try {
      mvc.perform(
              post("/api/receptions/" + a.reception() + "/business-day/close")
                  .with(csrf())
                  .with(TestUsers.as(jdbc, "lv.actor4")))
          .andExpect(status().isOk());
      assertThat(ra.events)
          .containsExactly(
              new LiveEvent.BusinessDayChanged(a.reception(), LocalDate.of(2030, 1, 11)));
      assertThat(rb.events).isEmpty();
    } finally {
      connections.unregister(ra);
      connections.unregister(rb);
    }
  }

  @Test
  void heartbeatDropsConnectionsWithExpiredSessionOrBrokenPipe() {
    Fx a = fx();
    long watcher = user("lv.watcher5", "RECEPTIONIST", a.reception());
    Recorder expired = listen(watcher);
    Recorder broken = listen(watcher);
    int before = connections.size();
    expired.valid = false;
    broken.closed = true;
    connections.dispatch(new LiveEvent.Heartbeat());
    assertThat(connections.size()).isEqualTo(before - 1); // pokazené spojenie sa upratalo
    // Heartbeat kontroluje aj reláciu
    try {
      var m = LiveConnections.class.getDeclaredMethod("heartbeat");
      m.setAccessible(true);
      m.invoke(connections);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    assertThat(connections.size()).isEqualTo(before - 2);
    assertThat(expired.closed).isTrue();
  }

  @Test
  void eventsEndpointOpensStreamWithProxyHeadersAndNeedsPlanView() throws Exception {
    user("lv.viewer", "RECEPTIONIST", null);
    mvc.perform(
            get("/api/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .with(TestUsers.as(jdbc, "lv.viewer")))
        .andExpect(request().asyncStarted())
        .andExpect(header().string("Cache-Control", "no-cache"))
        .andExpect(header().string("X-Accel-Buffering", "no"));

    TestUsers.create(jdbc, encoder, "lv.nobody", PASSWORD, false);
    mvc.perform(
            get("/api/events")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .with(TestUsers.as(jdbc, "lv.nobody")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/events").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isUnauthorized());
  }
}
