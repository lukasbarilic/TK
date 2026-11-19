package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

/** Poznámka a komentáre rezervačnej karty (TK-45). Fiktívne dáta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ReservationNoteIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired LiveConnections connections;

  private record Fx(long reception, long card, long watcherUser) {}

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

  /** Karta s jedným hosťom a jedným pobytom v novej recepcii. */
  private Fx fx() {
    int n = ++seq;
    long centre = id("insert into centre (code, name) values ('NT" + n + "', 'Test') returning id");
    long reception =
        id("insert into reception (code, name) values ('NTR" + n + "', 'Test') returning id");
    long building =
        id(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            centre,
            reception);
    long base = id("insert into base_category (name) values ('NTK" + n + "') returning id");
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
                    "insert into payer (code, name, type) values ('NTP', 'Platca', 'SELF')"
                        + " returning id"));
    long card =
        id(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id) values (?, 'RESERVED', '2030-01-10',"
                + " '2030-01-17', ?, ?) returning id",
            "NT" + System.nanoTime(),
            category,
            payer);
    long client =
        id(
            "insert into client (first_name, last_name, birth_date)"
                + " values ('Ukážka', 'Poznámkovská', '1970-01-01') returning id");
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest)"
            + " values (?, ?, true)",
        card,
        client);
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, '2030-01-10', '2030-01-17', ?)",
        card,
        bed,
        category);
    long watcher = TestUsers.create(jdbc, encoder, "nt.watcher" + n, PASSWORD, false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id)"
            + " select ?, id, ? from role where code = 'RECEPTIONIST'",
        watcher,
        reception);
    return new Fx(reception, card, watcher);
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
    return user("nt.recepcna", "RECEPTIONIST");
  }

  /** Používateľ len s oprávnením RESERVATION_VIEW (a PLAN_VIEW pre plán). */
  private RequestPostProcessor viewer() {
    if (jdbc.queryForObject("select count(*) from role where code = 'NT_VIEWER'", Long.class)
        == 0) {
      jdbc.update("insert into role (code, name) values ('NT_VIEWER', 'Len čítanie')");
      jdbc.update(
          "insert into role_permission (role_id, permission_code)"
              + " select r.id, p.code from role r, permission p"
              + " where r.code = 'NT_VIEWER' and p.code in ('RESERVATION_VIEW', 'PLAN_VIEW')");
    }
    return user("nt.citatel", "NT_VIEWER");
  }

  private long version(long card) {
    return id("select version from reservation where id = ?", card);
  }

  private MvcResult patchCard(RequestPostProcessor who, long card, String fields) throws Exception {
    return mvc.perform(
            patch("/api/reservations/" + card)
                .with(csrf())
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":" + version(card) + "," + fields + "}"))
        .andReturn();
  }

  private MvcResult addComment(RequestPostProcessor who, long card, String text) throws Exception {
    return mvc.perform(
            post("/api/reservations/" + card + "/comments")
                .with(csrf())
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\"}"))
        .andReturn();
  }

  private MvcResult hide(RequestPostProcessor who, long card, long comment) throws Exception {
    return mvc.perform(
            post("/api/reservations/" + card + "/comments/" + comment + "/hide")
                .with(csrf())
                .with(who))
        .andReturn();
  }

  private String body(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString();
  }

  private String getCard(long card) throws Exception {
    return body(mvc.perform(get("/api/reservations/" + card).with(receptionist())).andReturn());
  }

  private long seqOf(long reception) {
    return id("select seq from reception_change_seq where reception_id = ?", reception);
  }

  @Test
  void noteIsSavedShownAndClearedWithBlank() throws Exception {
    Fx fx = fx();

    MvcResult r =
        patchCard(receptionist(), fx.card(), "\"note\":\"Alergia na orechy\",\"noteInPlan\":true");

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(JsonPath.<String>read(body(r), "$.note")).isEqualTo("Alergia na orechy");
    assertThat(JsonPath.<Boolean>read(getCard(fx.card()), "$.noteInPlan")).isTrue();
    assertThat(JsonPath.<String>read(getCard(fx.card()), "$.note")).isEqualTo("Alergia na orechy");

    // neuvedené polia sa nemenia
    patchCard(receptionist(), fx.card(), "\"doNotMove\":true");
    assertThat(JsonPath.<String>read(getCard(fx.card()), "$.note")).isEqualTo("Alergia na orechy");

    patchCard(receptionist(), fx.card(), "\"note\":\"  \"");
    assertThat((String) JsonPath.read(getCard(fx.card()), "$.note")).isNull();
  }

  @Test
  void noteLengthLimitIs2000() throws Exception {
    Fx fx = fx();

    MvcResult ok = patchCard(receptionist(), fx.card(), "\"note\":\"" + "a".repeat(2000) + "\"");
    MvcResult tooLong =
        patchCard(receptionist(), fx.card(), "\"note\":\"" + "a".repeat(2001) + "\"");

    assertThat(ok.getResponse().getStatus()).isEqualTo(200);
    assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void noteUpdateWithStaleVersionIsConflict() throws Exception {
    Fx fx = fx();

    MvcResult r =
        mvc.perform(
                patch("/api/reservations/" + fx.card())
                    .with(csrf())
                    .with(receptionist())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":" + (version(fx.card()) + 5) + ",\"note\":\"x\"}"))
            .andReturn();

    assertThat(r.getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void commentCanBeAddedListedAndHiddenButNotEdited() throws Exception {
    Fx fx = fx();

    MvcResult created = addComment(receptionist(), fx.card(), "Hosť príde neskoro");
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    long commentId = JsonPath.<Integer>read(body(created), "$.id").longValue();
    assertThat(JsonPath.<String>read(body(created), "$.text")).isEqualTo("Hosť príde neskoro");
    assertThat(JsonPath.<String>read(body(created), "$.createdAt")).isNotBlank();
    assertThat(id("select author_id from reservation_comment where id = ?", commentId))
        .isEqualTo(id("select id from user_account where username = 'nt.recepcna'"));

    String list =
        body(
            mvc.perform(get("/api/reservations/" + fx.card() + "/comments").with(receptionist()))
                .andReturn());
    assertThat(JsonPath.<List<?>>read(list, "$")).hasSize(1);

    // úprava komentára nie je podporovaná
    MvcResult edit =
        mvc.perform(
                patch("/api/reservations/" + fx.card() + "/comments/" + commentId)
                    .with(csrf())
                    .with(receptionist())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"text\":\"iné\"}"))
            .andReturn();
    assertThat(edit.getResponse().getStatus()).isGreaterThanOrEqualTo(400);

    assertThat(hide(receptionist(), fx.card(), commentId).getResponse().getStatus()).isEqualTo(204);
    assertThat(
            jdbc.queryForObject(
                "select hidden_at is not null and hidden_by is not null"
                    + " from reservation_comment where id = ?",
                Boolean.class,
                commentId))
        .isTrue();
    String after =
        body(
            mvc.perform(get("/api/reservations/" + fx.card() + "/comments").with(receptionist()))
                .andReturn());
    assertThat(JsonPath.<List<?>>read(after, "$")).isEmpty();
    assertThat(JsonPath.<List<?>>read(getCard(fx.card()), "$.comments")).isEmpty();
    // opakované skrytie a cudzia karta
    assertThat(hide(receptionist(), fx.card(), commentId).getResponse().getStatus()).isEqualTo(404);
    Fx other = fx();
    long mine = JsonPath.<Integer>read(body(addComment(receptionist(), other.card(), "x")), "$.id");
    assertThat(hide(receptionist(), fx.card(), mine).getResponse().getStatus()).isEqualTo(404);
  }

  @Test
  void commentTextIsRequiredAndLimitedTo2000() throws Exception {
    Fx fx = fx();

    assertThat(addComment(receptionist(), fx.card(), "a".repeat(2000)).getResponse().getStatus())
        .isEqualTo(201);
    assertThat(addComment(receptionist(), fx.card(), "a".repeat(2001)).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(addComment(receptionist(), fx.card(), "   ").getResponse().getStatus())
        .isEqualTo(400);
    assertThat(addComment(receptionist(), Long.MAX_VALUE, "x").getResponse().getStatus())
        .isEqualTo(404);
  }

  @Test
  void permissionsAreEnforced() throws Exception {
    Fx fx = fx();
    long commentId =
        JsonPath.<Integer>read(body(addComment(receptionist(), fx.card(), "Pre čítanie")), "$.id");
    RequestPostProcessor viewer = viewer();
    RequestPostProcessor nobody = user("nt.nikto");

    assertThat(
            mvc.perform(get("/api/reservations/" + fx.card() + "/comments").with(viewer))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    assertThat(addComment(viewer, fx.card(), "x").getResponse().getStatus()).isEqualTo(403);
    assertThat(hide(viewer, fx.card(), commentId).getResponse().getStatus()).isEqualTo(403);
    assertThat(
            mvc.perform(get("/api/reservations/" + fx.card() + "/comments").with(nobody))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(patchCard(viewer, fx.card(), "\"note\":\"x\"").getResponse().getStatus())
        .isEqualTo(403);
  }

  @Test
  void detailHoldsLast20CommentsOldestFirst() throws Exception {
    Fx fx = fx();
    for (int i = 1; i <= 22; i++) {
      addComment(receptionist(), fx.card(), "K" + i);
    }

    String json = getCard(fx.card());

    assertThat(JsonPath.<List<?>>read(json, "$.comments")).hasSize(20);
    assertThat(JsonPath.<String>read(json, "$.comments[0].text")).isEqualTo("K3");
    assertThat(JsonPath.<String>read(json, "$.comments[19].text")).isEqualTo("K22");
    assertThat(
            JsonPath.<List<?>>read(
                body(
                    mvc.perform(
                            get("/api/reservations/" + fx.card() + "/comments")
                                .with(receptionist()))
                        .andReturn()),
                "$"))
        .hasSize(22);
  }

  @Test
  void planBarHasNoteFlagOnlyWhenNoteInPlanAndCommentCount() throws Exception {
    Fx fx = fx();
    String url =
        "/api/accommodation-plan?from=2030-01-10&to=2030-01-20&receptionId=" + fx.reception();
    String bar = "$.rooms[0].bars[?(@.type=='STAY')]";

    String plain = body(mvc.perform(get(url).with(receptionist())).andReturn());
    assertThat(JsonPath.<List<Boolean>>read(plain, bar + ".hasNote")).containsExactly(false);
    assertThat(JsonPath.<List<Integer>>read(plain, bar + ".commentCount")).containsExactly(0);

    patchCard(receptionist(), fx.card(), "\"note\":\"Dôležité\",\"noteInPlan\":false");
    long c1 = JsonPath.<Integer>read(body(addComment(receptionist(), fx.card(), "a")), "$.id");
    addComment(receptionist(), fx.card(), "b");
    String hidden = body(mvc.perform(get(url).with(receptionist())).andReturn());
    assertThat(JsonPath.<List<Boolean>>read(hidden, bar + ".hasNote")).containsExactly(false);
    assertThat(JsonPath.<List<Integer>>read(hidden, bar + ".commentCount")).containsExactly(2);

    patchCard(receptionist(), fx.card(), "\"noteInPlan\":true");
    hide(receptionist(), fx.card(), c1);
    String shown = body(mvc.perform(get(url).with(receptionist())).andReturn());
    assertThat(JsonPath.<List<Boolean>>read(shown, bar + ".hasNote")).containsExactly(true);
    assertThat(JsonPath.<List<Integer>>read(shown, bar + ".commentCount")).containsExactly(1);
  }

  @Test
  void offlinePackageContainsNoteFlagAndComments() throws Exception {
    Fx fx = fx();
    patchCard(receptionist(), fx.card(), "\"note\":\"Offline poznámka\",\"noteInPlan\":true");
    addComment(receptionist(), fx.card(), "Offline komentár");
    long hiddenId =
        JsonPath.<Integer>read(body(addComment(receptionist(), fx.card(), "Skrytý")), "$.id");
    hide(receptionist(), fx.card(), hiddenId);

    String json =
        body(
            mvc.perform(get("/api/offline/reservations?ids=" + fx.card()).with(receptionist()))
                .andReturn());

    assertThat(JsonPath.<String>read(json, "$[0].note")).isEqualTo("Offline poznámka");
    assertThat(JsonPath.<Boolean>read(json, "$[0].noteInPlan")).isTrue();
    assertThat(JsonPath.<List<String>>read(json, "$[0].comments[*].text"))
        .containsExactly("Offline komentár");
  }

  @Test
  void writesAreAuditedBumpSeqAndSendPlanChanged() throws Exception {
    Fx fx = fx();
    Recorder recorder = new Recorder();
    connections.register(fx.watcherUser(), recorder);
    try {
      long before = seqOf(fx.reception());
      MvcResult note = patchCard(receptionist(), fx.card(), "\"note\":\"Audit\"");
      long afterNote = seqOf(fx.reception());
      MvcResult comment = addComment(receptionist(), fx.card(), "Audit komentár");
      long afterComment = seqOf(fx.reception());
      long commentId = JsonPath.<Integer>read(body(comment), "$.id");
      MvcResult hidden = hide(receptionist(), fx.card(), commentId);

      assertThat(afterNote).isGreaterThan(before);
      assertThat(afterComment).isGreaterThan(afterNote);
      assertThat(seqOf(fx.reception())).isGreaterThan(afterComment);
      assertThat(recorder.events).hasSize(3);
      assertThat(recorder.events).allMatch(e -> e instanceof LiveEvent.PlanChanged);
      assertThat(operation(note)).isEqualTo("RESERVATION_UPDATE/SUCCESS");
      assertThat(operation(comment)).isEqualTo("RESERVATION_COMMENT_ADD/SUCCESS");
      assertThat(operation(hidden)).isEqualTo("RESERVATION_COMMENT_HIDE/SUCCESS");
    } finally {
      connections.unregister(recorder);
    }
  }

  @Test
  void readingCommentsIsAuditedAsContactRead() throws Exception {
    Fx fx = fx();
    addComment(receptionist(), fx.card(), "Čítanie");

    MvcResult r =
        mvc.perform(get("/api/reservations/" + fx.card() + "/comments").with(receptionist()))
            .andReturn();

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    long client = id("select client_id from reservation_guest where reservation_id = ?", fx.card());
    assertThat(
            jdbc.queryForList(
                "select a.data_scope from audit_subject_access a"
                    + " join audit_event e on e.id = a.audit_event_id"
                    + " where e.operation = 'RESERVATION_VIEW' and a.client_id = ?",
                String.class,
                client))
        .containsExactly("CONTACT");
  }

  private String operation(MvcResult r) {
    return jdbc.queryForObject(
        "select operation || '/' || outcome from audit_event where id = ?",
        String.class,
        Long.parseLong(r.getResponse().getHeader("X-Audit-Event-Id")));
  }
}
