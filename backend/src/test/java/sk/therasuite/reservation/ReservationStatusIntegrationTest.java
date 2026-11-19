package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.notification.application.LiveConnection;
import sk.therasuite.notification.application.LiveConnections;
import sk.therasuite.notification.application.LiveEvent;

/** Stavy rezervačnej karty (TK-43): prechody, uvoľnenie pobytov, oprávnenia, audit a SSE. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ReservationStatusIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static final String DAY = "2030-01-10";
  private static int seq;
  private final List<Long> cards = new ArrayList<>();

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired LiveConnections connections;

  /** Recepcia s obchodným dňom 2030-01-10 a izbou s dvoma lôžkami. */
  private record Fx(long reception, long category, long room, long bed1, long bed2) {}

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

  /** Karty testu sa nesmú zostať v stavoch, ktoré by rušili pravidlá uzávierky iných testov. */
  @AfterEach
  void retireCards() {
    for (long id : cards) {
      jdbc.update("update reservation set status = 'CANCELLED' where id = ?", id);
    }
    cards.clear();
  }

  private Fx fx() {
    int n = ++seq;
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values ('RS" + n + "', 'Test') returning id",
            Long.class);
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values ('RSR" + n + "', 'Test') returning id",
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
            "insert into base_category (name) values ('RSK" + n + "') returning id", Long.class);
    long category =
        jdbc.queryForObject(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'K', 'Kat', 2) returning id",
            Long.class,
            centre,
            base);
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, ?::date, 'OPEN')",
        reception,
        DAY);
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
    return new Fx(reception, category, room, bed(room, 1), bed(room, 2));
  }

  private long bed(long room, int number) {
    return jdbc.queryForObject(
        "select id from bed where room_id = ? and bed_number = ?", Long.class, room, number);
  }

  private long payer() {
    return jdbc.queryForList("select id from payer limit 1", Long.class).stream()
        .findFirst()
        .orElseGet(() ->
            jdbc.queryForObject(
                "insert into payer (code, name, type) values ('RSP', 'Platca', 'SELF') returning id",
                Long.class));
  }

  /** Karta so zadaným stavom a počtom hostí (fiktívni hostia). */
  private long card(Fx fx, String status, String arrival, String departure, int guests) {
    long id =
        jdbc.queryForObject(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id) values (?, ?, ?::date, ?::date, ?, ?)"
                + " returning id",
            Long.class,
            "RS" + System.nanoTime(),
            status,
            arrival,
            departure,
            fx.category(),
            payer());
    cards.add(id);
    for (int i = 0; i < guests; i++) {
      long client =
          jdbc.queryForObject(
              "insert into client (first_name, last_name, birth_date)"
                  + " values ('Ukážka', 'Hosťovská', '1970-01-01') returning id",
              Long.class);
      jdbc.update(
          "insert into reservation_guest (reservation_id, client_id, primary_guest)"
              + " values (?, ?, ?)",
          id,
          client,
          i == 0);
    }
    return id;
  }

  private long stay(long card, long bed, Fx fx, String from, String to) {
    return jdbc.queryForObject(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, ?::date, ?::date, ?) returning id",
        Long.class,
        card,
        bed,
        from,
        to,
        fx.category());
  }

  private RequestPostProcessor user(String username, String role) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, role);
    }
    return TestUsers.as(jdbc, username);
  }

  private RequestPostProcessor receptionist() {
    return user("rs.recepcna", "RECEPTIONIST");
  }

  private RequestPostProcessor manager() {
    return user("rs.veduca", "RECEPTION_MANAGER");
  }

  private long version(long card) {
    return jdbc.queryForObject("select version from reservation where id = ?", Long.class, card);
  }

  private String statusOf(long card) {
    return jdbc.queryForObject("select status from reservation where id = ?", String.class, card);
  }

  private MvcResult act(RequestPostProcessor who, long card, String action, String body)
      throws Exception {
    return mvc.perform(
            post("/api/reservations/" + card + "/" + action)
                .with(csrf())
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private MvcResult act(RequestPostProcessor who, long card, String action) throws Exception {
    return act(who, card, action, "{\"version\":" + version(card) + "}");
  }

  private static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }

  private static String code(MvcResult r) throws Exception {
    return JsonPath.read(r.getResponse().getContentAsString(), "$.code");
  }

  @Test
  void checkInSetsStatusTimestampAndReturnsDetail() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);
    stay(card, fx.bed1(), fx, DAY, "2030-01-17");
    long before = version(card);

    MvcResult r = act(receptionist(), card, "check-in");

    assertThat(status(r)).isEqualTo(200);
    String json = r.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("CHECKED_IN");
    assertThat(JsonPath.<String>read(json, "$.checkedInAt")).isNotBlank();
    assertThat(JsonPath.<Integer>read(json, "$.version").longValue()).isGreaterThan(before);
    assertThat(JsonPath.<List<?>>read(json, "$.guests")).hasSize(1);
    assertThat(statusOf(card)).isEqualTo("CHECKED_IN");
  }

  @Test
  void checkInWritesAuditWithOldAndNewStateBumpsSeqAndSendsPlanChanged() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);
    stay(card, fx.bed1(), fx, DAY, "2030-01-17");
    long watcher = TestUsers.create(jdbc, encoder, "rs.watcher" + seq, PASSWORD, false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id)"
            + " select ?, id, ? from role where code = 'RECEPTIONIST'",
        watcher,
        fx.reception());
    long seqBefore =
        jdbc.queryForObject(
            "select seq from reception_change_seq where reception_id = ?",
            Long.class,
            fx.reception());
    Recorder recorder = new Recorder();
    connections.register(watcher, recorder);
    try {
      MvcResult r = act(receptionist(), card, "check-in");

      assertThat(status(r)).isEqualTo(200);
      long eventId = Long.parseLong(r.getResponse().getHeader("X-Audit-Event-Id"));
      assertThat(
              jdbc.queryForObject(
                  "select operation || '/' || outcome from audit_event where id = ?",
                  String.class,
                  eventId))
          .isEqualTo("RESERVATION_CHECK_IN/SUCCESS");
      assertThat(
              jdbc.queryForObject(
                  "select concat(before_state->>'status', '>', after_state->>'status')"
                      + " from audit_change where audit_event_id = ? and entity_type = 'reservation'"
                      + " and change_type = 'UPDATE'",
                  String.class,
                  eventId))
          .isEqualTo("RESERVED>CHECKED_IN");
      assertThat(
              jdbc.queryForObject(
                  "select seq from reception_change_seq where reception_id = ?",
                  Long.class,
                  fx.reception()))
          .isGreaterThan(seqBefore);
      assertThat(recorder.events).hasSize(1);
      LiveEvent.PlanChanged e = (LiveEvent.PlanChanged) recorder.events.get(0);
      assertThat(e.receptionId()).isEqualTo(fx.reception());
      assertThat(e.roomIds()).containsExactly(fx.room());
      assertThat(e.auditEventId()).isEqualTo(eventId);
    } finally {
      connections.unregister(recorder);
    }
  }

  @Test
  void checkInWithoutBedForAllGuestsIsRejected() throws Exception {
    Fx fx = fx();
    long noBed = card(fx, "RESERVED", DAY, "2030-01-17", 1);
    long oneOfTwo = card(fx, "RESERVED", DAY, "2030-01-17", 2);
    stay(oneOfTwo, fx.bed1(), fx, DAY, "2030-01-17");

    MvcResult a = act(receptionist(), noBed, "check-in");
    MvcResult b = act(receptionist(), oneOfTwo, "check-in");

    assertThat(status(a)).isEqualTo(409);
    assertThat(code(a)).isEqualTo("NO_BED_ASSIGNED");
    assertThat(status(b)).isEqualTo(409);
    assertThat(code(b)).isEqualTo("NO_BED_ASSIGNED");
    assertThat(statusOf(noBed)).isEqualTo("RESERVED");
    assertThat(statusOf(oneOfTwo)).isEqualTo("RESERVED");
  }

  @Test
  void checkInBeforeArrivalDayIsRejectedButLateArrivalIsAllowed() throws Exception {
    Fx fx = fx();
    long future = card(fx, "RESERVED", "2030-01-11", "2030-01-17", 1);
    stay(future, fx.bed1(), fx, "2030-01-11", "2030-01-17");
    long late = card(fx, "RESERVED", "2030-01-08", "2030-01-17", 1);
    stay(late, fx.bed2(), fx, "2030-01-08", "2030-01-17");

    MvcResult rejected = act(receptionist(), future, "check-in");

    assertThat(status(rejected)).isEqualTo(409);
    assertThat(code(rejected)).isEqualTo("ARRIVAL_IN_FUTURE");
    assertThat(status(act(receptionist(), late, "check-in"))).isEqualTo(200);
    assertThat(statusOf(late)).isEqualTo("CHECKED_IN");
  }

  @Test
  void checkOutOnDepartureDayNeedsNoConfirmation() throws Exception {
    Fx fx = fx();
    long card = card(fx, "CHECKED_IN", "2030-01-05", DAY, 1);
    stay(card, fx.bed1(), fx, "2030-01-05", DAY);

    MvcResult r = act(receptionist(), card, "check-out", "{\"version\":" + version(card) + "}");

    assertThat(status(r)).isEqualTo(200);
    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.status"))
        .isEqualTo("CHECKED_OUT");
    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.checkedOutAt"))
        .isNotBlank();
  }

  @Test
  void earlyCheckOutNeedsConfirmationAndShortensStays() throws Exception {
    Fx fx = fx();
    long card = card(fx, "CHECKED_IN", "2030-01-08", "2030-01-17", 1);
    long stay = stay(card, fx.bed1(), fx, "2030-01-08", "2030-01-17");

    MvcResult warn = act(receptionist(), card, "check-out", "{\"version\":" + version(card) + "}");

    assertThat(status(warn)).isEqualTo(409);
    assertThat(code(warn)).isEqualTo("CONFIRMATION_REQUIRED");
    String json = warn.getResponse().getContentAsString();
    assertThat(JsonPath.<Boolean>read(json, "$.confirmable")).isTrue();
    assertThat(JsonPath.<String>read(json, "$.warnings[0].code")).isEqualTo("EARLY_DEPARTURE");
    assertThat(statusOf(card)).isEqualTo("CHECKED_IN");

    MvcResult ok =
        act(
            receptionist(),
            card,
            "check-out",
            "{\"version\":" + version(card) + ",\"confirm\":[\"EARLY_DEPARTURE\"]}");

    assertThat(status(ok)).isEqualTo(200);
    assertThat(statusOf(card)).isEqualTo("CHECKED_OUT");
    assertThat(
            jdbc.queryForObject("select date_to::text from stay where id = ?", String.class, stay))
        .isEqualTo(DAY);
  }

  @Test
  void cancelRequiresReasonAndReleasesBeds() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", "2030-01-12", "2030-01-17", 1);
    stay(card, fx.bed1(), fx, "2030-01-12", "2030-01-17");

    MvcResult noReason = act(receptionist(), card, "cancel", "{\"version\":" + version(card) + "}");
    MvcResult blank =
        act(
            receptionist(),
            card,
            "cancel",
            "{\"version\":" + version(card) + ",\"reason\":\"   \"}");
    assertThat(status(noReason)).isEqualTo(400);
    assertThat(status(blank)).isEqualTo(400);

    MvcResult r =
        act(
            receptionist(),
            card,
            "cancel",
            "{\"version\":" + version(card) + ",\"reason\":\"Hosť ochorel\"}");

    assertThat(status(r)).isEqualTo(200);
    String json = r.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("CANCELLED");
    assertThat(JsonPath.<String>read(json, "$.cancelReason")).isEqualTo("Hosť ochorel");
    assertThat(JsonPath.<String>read(json, "$.cancelledAt")).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from stay where reservation_id = ?", Integer.class, card))
        .isZero();
    // Lôžko je voľné pre inú kartu.
    long other = card(fx, "RESERVED", "2030-01-12", "2030-01-17", 1);
    stay(other, fx.bed1(), fx, "2030-01-12", "2030-01-17");
  }

  @Test
  void cancelRejectsTooLongReason() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);

    MvcResult r =
        act(
            receptionist(),
            card,
            "cancel",
            "{\"version\":" + version(card) + ",\"reason\":\"" + "x".repeat(501) + "\"}");

    assertThat(status(r)).isEqualTo(400);
    assertThat(statusOf(card)).isEqualTo("RESERVED");
  }

  @Test
  void noShowReleasesStaysFromBusinessDayOnlyWhenArrivalIsNotInFuture() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", "2030-01-08", "2030-01-17", 1);
    long stay = stay(card, fx.bed1(), fx, "2030-01-08", "2030-01-17");
    long future = card(fx, "RESERVED", "2030-01-12", "2030-01-17", 1);

    MvcResult tooEarly = act(receptionist(), future, "no-show");
    assertThat(status(tooEarly)).isEqualTo(409);
    assertThat(code(tooEarly)).isEqualTo("NO_SHOW_IN_FUTURE");

    MvcResult r = act(receptionist(), card, "no-show");

    assertThat(status(r)).isEqualTo(200);
    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.status"))
        .isEqualTo("NO_SHOW");
    // Minulé noci zostávajú, od obchodného dňa je lôžko voľné.
    assertThat(
            jdbc.queryForObject("select date_to::text from stay where id = ?", String.class, stay))
        .isEqualTo(DAY);
  }

  @Test
  void undoCheckInWorksOnlyOnArrivalDayAndNeedsFixPermission() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);
    stay(card, fx.bed1(), fx, DAY, "2030-01-17");
    assertThat(status(act(receptionist(), card, "check-in"))).isEqualTo(200);

    assertThat(status(act(receptionist(), card, "undo-check-in"))).isEqualTo(403);
    MvcResult r = act(manager(), card, "undo-check-in");

    assertThat(status(r)).isEqualTo(200);
    String json = r.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "select checked_in_at is null from reservation where id = ?", Boolean.class, card))
        .isTrue();

    long late = card(fx, "CHECKED_IN", "2030-01-08", "2030-01-17", 1);
    stay(late, fx.bed2(), fx, "2030-01-08", "2030-01-17");
    MvcResult notToday = act(manager(), late, "undo-check-in");
    assertThat(status(notToday)).isEqualTo(409);
    assertThat(code(notToday)).isEqualTo("UNDO_CHECK_IN_NOT_ALLOWED");
  }

  @Test
  void revertNoShowRestoresStatusButNotTheBed() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", "2030-01-10", "2030-01-17", 1);
    stay(card, fx.bed1(), fx, "2030-01-10", "2030-01-17");
    assertThat(status(act(receptionist(), card, "no-show"))).isEqualTo(200);

    assertThat(status(act(receptionist(), card, "revert-no-show"))).isEqualTo(403);
    MvcResult r = act(manager(), card, "revert-no-show");

    assertThat(status(r)).isEqualTo(200);
    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.status"))
        .isEqualTo("RESERVED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from stay where reservation_id = ?", Integer.class, card))
        .isZero();
  }

  @Test
  void forbiddenTransitionsReturnInvalidStatusTransition() throws Exception {
    Fx fx = fx();
    String[][] cases = {
      // stav karty, akcia, telo
      {"CHECKED_IN", "check-in", ""},
      {"CHECKED_OUT", "check-in", ""},
      {"CANCELLED", "check-in", ""},
      {"NO_SHOW", "check-in", ""},
      {"RESERVED", "check-out", ""},
      {"CANCELLED", "check-out", ""},
      {"NO_SHOW", "check-out", ""},
      {"CHECKED_IN", "cancel", ",\"reason\":\"dôvod\""},
      {"CHECKED_OUT", "cancel", ",\"reason\":\"dôvod\""},
      {"NO_SHOW", "cancel", ",\"reason\":\"dôvod\""},
      {"CHECKED_IN", "no-show", ""},
      {"CANCELLED", "no-show", ""},
      {"RESERVED", "undo-check-in", ""},
      {"CHECKED_OUT", "undo-check-in", ""},
      {"RESERVED", "revert-no-show", ""},
      {"CANCELLED", "revert-no-show", ""},
    };
    for (String[] c : cases) {
      long card = card(fx, c[0], DAY, "2030-01-17", 1);
      MvcResult r = act(manager(), card, c[1], "{\"version\":" + version(card) + c[2] + "}");
      assertThat(status(r)).as(c[0] + " → " + c[1]).isEqualTo(409);
      assertThat(code(r)).as(c[0] + " → " + c[1]).isEqualTo("INVALID_STATUS_TRANSITION");
      assertThat(statusOf(card)).isEqualTo(c[0]);
    }
  }

  @Test
  void staleVersionIsRejected() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);
    stay(card, fx.bed1(), fx, DAY, "2030-01-17");

    MvcResult r =
        act(receptionist(), card, "check-in", "{\"version\":" + (version(card) + 5) + "}");

    assertThat(status(r)).isEqualTo(409);
    assertThat(code(r)).isEqualTo("CONFLICT");
    assertThat(statusOf(card)).isEqualTo("RESERVED");
  }

  @Test
  void missingVersionIsBadRequestAndUsersWithoutPermissionAreForbidden() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);
    stay(card, fx.bed1(), fx, DAY, "2030-01-17");

    assertThat(status(act(receptionist(), card, "check-in", "{}"))).isEqualTo(400);
    assertThat(status(act(user("rs.lekar", "DOCTOR"), card, "check-in"))).isEqualTo(403);
    assertThat(
            status(
                act(
                    user("rs.lekar", "DOCTOR"),
                    card,
                    "cancel",
                    "{\"version\":" + version(card) + ",\"reason\":\"x\"}")))
        .isEqualTo(403);
    assertThat(statusOf(card)).isEqualTo("RESERVED");
  }

  @Test
  void seedGivesFixPermissionOnlyToReceptionManager() {
    assertThat(permissionsOf("RECEPTIONIST"))
        .contains("RESERVATION_STATUS")
        .doesNotContain("RESERVATION_STATUS_FIX");
    assertThat(permissionsOf("RECEPTION_MANAGER"))
        .contains("RESERVATION_STATUS", "RESERVATION_STATUS_FIX");
  }

  private List<String> permissionsOf(String role) {
    return jdbc.queryForList(
        "select rp.permission_code from role_permission rp join role r on r.id = rp.role_id"
            + " where r.code = ?",
        String.class,
        role);
  }

  @Test
  void assigningBedDoesNotChangeCardStatus() throws Exception {
    Fx fx = fx();
    long card = card(fx, "RESERVED", DAY, "2030-01-17", 1);

    MvcResult r =
        mvc.perform(
                post("/api/reservations/" + card + "/stays")
                    .with(csrf())
                    .with(receptionist())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"bedId\":%d,\"from\":\"%s\",\"to\":\"2030-01-17\"}"
                            .formatted(fx.bed1(), DAY)))
            .andReturn();

    assertThat(status(r)).isEqualTo(201);
    assertThat(statusOf(card)).isEqualTo("RESERVED");
  }
}
