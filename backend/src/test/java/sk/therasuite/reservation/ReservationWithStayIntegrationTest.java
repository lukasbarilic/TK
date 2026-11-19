package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

/** Vyhľadanie klienta, založenie karty s lekárom/príznakmi a s-lôžkom (TK-46). Fiktívne dáta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ReservationWithStayIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager tx;

  private record Fx(long reception, long category, long payer, long bed1, long bed2, long extra) {}

  private long id(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private Fx fx() {
    int n = ++seq;
    long centre = id("insert into centre (code, name) values ('WS" + n + "', 'Test') returning id");
    long reception =
        id("insert into reception (code, name) values ('WSR" + n + "', 'Test') returning id");
    long building =
        id(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            centre,
            reception);
    long base = id("insert into base_category (name) values ('WSK" + n + "') returning id");
    long category =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'K', 'Kat', 2) returning id",
            centre,
            base);
    jdbc.update(
        "insert into business_day (reception_id, business_date, status)"
            + " values (?, '2030-01-10', 'OPEN')",
        reception);
    long room =
        new TransactionTemplate(tx)
            .execute(s -> {
              long r =
                  id(
                      "insert into room (building_id, number, category_id, regular_beds, extra_beds)"
                          + " values (?, '1', ?, 2, 1) returning id",
                      building,
                      category);
              for (int i = 1; i <= 3; i++) {
                jdbc.update(
                    "insert into bed (room_id, bed_number, type) values (?, ?, ?)",
                    r,
                    i,
                    i <= 2 ? "REGULAR" : "EXTRA");
              }
              return r;
            });
    long payer =
        jdbc.queryForList("select id from payer limit 1", Long.class).stream()
            .findFirst()
            .orElseGet(() ->
                id(
                    "insert into payer (code, name, type) values ('WSP', 'Platca', 'SELF')"
                        + " returning id"));
    return new Fx(
        reception,
        category,
        payer,
        id("select id from bed where room_id = ? and bed_number = 1", room),
        id("select id from bed where room_id = ? and bed_number = 2", room),
        id("select id from bed where room_id = ? and bed_number = 3", room));
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
    return user("ws.recepcna", "RECEPTIONIST");
  }

  private static String newGuest(String first, String last) {
    return "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"birthDate\":\"1970-01-01\",\"nationality\":\"SK\"}"
        .formatted(first, last);
  }

  private static String cardJson(
      Fx fx, String arrival, String departure, String guest, String extra) {
    return "\"arrivalDate\":\"%s\",\"departureDate\":\"%s\",\"bookedCategoryId\":%d,\"payerId\":%d,\"adults\":1,\"guests\":[%s]%s"
        .formatted(arrival, departure, fx.category(), fx.payer(), guest, extra);
  }

  private MvcResult send(RequestPostProcessor who, String url, String body) throws Exception {
    return mvc.perform(
            post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn();
  }

  private static String json(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString();
  }

  private long cardsOf(String lastName) {
    return id(
        "select count(*) from reservation_guest g join client c on c.id = g.client_id"
            + " where c.last_name = ?",
        lastName);
  }

  @Test
  void searchIgnoresDiacriticsReturnsLastStayAndAuditsNameRead() throws Exception {
    Fx fx = fx();
    String last = "Žľabová" + System.nanoTime();
    long client =
        id(
            "insert into client (first_name, last_name, birth_date)"
                + " values ('Ľubomíra', ?, '1971-03-04') returning id",
            last);
    long card =
        id(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id) values (?, 'CHECKED_OUT', '2029-05-01',"
                + " '2029-05-08', ?, ?) returning id",
            "WS" + System.nanoTime(),
            fx.category(),
            fx.payer());
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest)"
            + " values (?, ?, true)",
        card,
        client);

    String ascii = last.toLowerCase().replace("ž", "z").replace("ľ", "l").replace("á", "a");
    MvcResult result =
        mvc.perform(
                get("/api/clients")
                    .param("q", "lubomira " + ascii)
                    .param("limit", "10")
                    .with(receptionist()))
            .andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    String body = json(result);
    assertThat(JsonPath.<List<?>>read(body, "$")).hasSize(1);
    assertThat(JsonPath.<Integer>read(body, "$[0].id").longValue()).isEqualTo(client);
    assertThat(JsonPath.<String>read(body, "$[0].firstName")).isEqualTo("Ľubomíra");
    assertThat(JsonPath.<Integer>read(body, "$[0].birthYear")).isEqualTo(1971);
    assertThat(JsonPath.<String>read(body, "$[0].lastStayDate")).isEqualTo("2029-05-08");
    assertThat(body).doesNotContain("email").doesNotContain("phone");
    assertThat(
            id(
                "select count(*) from audit_event e join audit_subject_access a"
                    + " on a.audit_event_id = e.id where e.event_type = 'READ'"
                    + " and e.operation = 'CLIENT_SEARCH' and a.data_scope = 'NAME'"
                    + " and a.client_id = ?",
                client))
        .isEqualTo(1);
  }

  @Test
  void searchRequiresReservationEditAndValidLimit() throws Exception {
    assertThat(
            mvc.perform(get("/api/clients").param("q", "a").with(user("ws.lekar", "DOCTOR")))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(
            mvc.perform(get("/api/clients").param("limit", "0").with(receptionist()))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void receptionRolesHaveReservationEditAndStayCreateInSeed() {
    for (String role : List.of("RECEPTIONIST", "RECEPTION_MANAGER")) {
      assertThat(
              jdbc.queryForList(
                  "select permission_code from role_permission rp join role r on r.id = rp.role_id"
                      + " where r.code = ? and permission_code in ('RESERVATION_EDIT', 'STAY_CREATE')",
                  String.class,
                  role))
          .containsExactlyInAnyOrder("RESERVATION_EDIT", "STAY_CREATE");
    }
  }

  @Test
  void createStoresDoctorFlagsAndNoteWithNewAndExistingClient() throws Exception {
    Fx fx = fx();
    String last = "Karta" + System.nanoTime();
    long doctor = id("select id from user_account where username = ?", doctorUser());

    MvcResult created =
        send(
            receptionist(),
            "/api/reservations",
            "{"
                + cardJson(
                    fx,
                    "2030-05-01",
                    "2030-05-08",
                    newGuest("Nový", last),
                    ",\"attendingDoctorId\":%d,\"earlyCheckIn\":true,\"lateCheckOut\":true,\"doNotMove\":true,\"note\":\" Diéta \",\"noteInPlan\":true"
                        .formatted(doctor))
                + "}");
    assertThat(created.getResponse().getStatus()).isEqualTo(201);
    String body = json(created);
    assertThat(JsonPath.<Boolean>read(body, "$.earlyCheckIn")).isTrue();
    assertThat(JsonPath.<Boolean>read(body, "$.lateCheckOut")).isTrue();
    assertThat(JsonPath.<Boolean>read(body, "$.doNotMove")).isTrue();
    assertThat(JsonPath.<String>read(body, "$.note")).isEqualTo("Diéta");
    assertThat(JsonPath.<Boolean>read(body, "$.noteInPlan")).isTrue();
    long card = JsonPath.<Integer>read(body, "$.id").longValue();
    assertThat(id("select attending_doctor_id from reservation where id = ?", card))
        .isEqualTo(doctor);

    long clientId = JsonPath.<Integer>read(body, "$.guests[0].clientId").longValue();
    MvcResult existing =
        send(
            receptionist(),
            "/api/reservations",
            "{"
                + cardJson(fx, "2030-06-01", "2030-06-08", "{\"clientId\":" + clientId + "}", "")
                + "}");
    assertThat(existing.getResponse().getStatus()).isEqualTo(201);
    assertThat(id("select count(*) from client where last_name = ?", last)).isEqualTo(1);
  }

  @Test
  void createRejectsNonDoctorAsAttendingDoctor() throws Exception {
    Fx fx = fx();
    long notDoctor = id("select id from user_account where username = ?", receptionistName());
    MvcResult result =
        send(
            receptionist(),
            "/api/reservations",
            "{"
                + cardJson(
                    fx,
                    "2030-05-01",
                    "2030-05-08",
                    newGuest("Bez", "Lekára"),
                    ",\"attendingDoctorId\":" + notDoctor)
                + "}");
    assertThat(result.getResponse().getStatus()).isEqualTo(400);
  }

  private String doctorUser() {
    user("ws.lekar", "DOCTOR");
    return "ws.lekar";
  }

  private String receptionistName() {
    receptionist();
    return "ws.recepcna";
  }

  @Test
  void withStayCreatesCardAndStayWithAuditChangeSeqAndSse() throws Exception {
    Fx fx = fx();
    long before = changeSeq(fx.reception());
    String last = "Pobyt" + System.nanoTime();

    MvcResult result =
        send(
            receptionist(),
            "/api/reservations/with-stay",
            "{"
                + cardJson(
                    fx,
                    "2030-01-10",
                    "2030-01-14",
                    newGuest("Nový", last),
                    ",\"bedId\":" + fx.bed1())
                + "}");

    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    String body = json(result);
    long card = JsonPath.<Integer>read(body, "$.reservation.id").longValue();
    assertThat(JsonPath.<Integer>read(body, "$.stay.bedId").longValue()).isEqualTo(fx.bed1());
    assertThat(JsonPath.<String>read(body, "$.stay.dateFrom")).isEqualTo("2030-01-10");
    assertThat(JsonPath.<String>read(body, "$.stay.dateTo")).isEqualTo("2030-01-14");
    assertThat(id("select count(*) from stay where reservation_id = ?", card)).isEqualTo(1);

    long event = Long.parseLong(result.getResponse().getHeader("X-Audit-Event-Id"));
    assertThat(
            jdbc.queryForObject(
                "select operation from audit_event where id = ?", String.class, event))
        .isEqualTo("RESERVATION_CREATE_WITH_STAY");
    assertThat(
            jdbc.queryForList(
                "select distinct entity_type from audit_change where audit_event_id = ?",
                String.class,
                event))
        .contains("reservation", "client", "reservation_guest", "stay");
    assertThat(changeSeq(fx.reception())).isGreaterThan(before);
  }

  @Test
  void withStayOnOccupiedBedReturns409AndCardIsNotCreated() throws Exception {
    Fx fx = fx();
    assertThat(
            send(
                    receptionist(),
                    "/api/reservations/with-stay",
                    "{"
                        + cardJson(
                            fx,
                            "2030-01-10",
                            "2030-01-14",
                            newGuest("Prvý", "Obsadený"),
                            ",\"bedId\":" + fx.bed1())
                        + "}")
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    String last = "Druhý" + System.nanoTime();
    long cardsBefore = id("select count(*) from reservation");

    MvcResult clash =
        send(
            receptionist(),
            "/api/reservations/with-stay",
            "{"
                + cardJson(
                    fx,
                    "2030-01-12",
                    "2030-01-16",
                    newGuest("Druhý", last),
                    ",\"bedId\":" + fx.bed1())
                + "}");

    assertThat(clash.getResponse().getStatus()).isEqualTo(409);
    assertThat(JsonPath.<String>read(json(clash), "$.code")).isEqualTo("BED_OCCUPIED");
    assertThat(cardsOf(last)).isZero();
    assertThat(id("select count(*) from client where last_name = ?", last)).isZero();
    assertThat(id("select count(*) from reservation")).isEqualTo(cardsBefore);
  }

  @Test
  void withStayRejectsExtraBedFlagOnRegularBedAndAcceptsRealExtraBed() throws Exception {
    Fx fx = fx();
    String last = "Prístelka" + System.nanoTime();
    MvcResult wrong =
        send(
            receptionist(),
            "/api/reservations/with-stay",
            "{"
                + cardJson(
                    fx,
                    "2030-01-10",
                    "2030-01-12",
                    newGuest("Ukážka", last),
                    ",\"bedId\":" + fx.bed2() + ",\"extraBed\":true")
                + "}");
    assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
    assertThat(cardsOf(last)).isZero();

    String child =
        "{\"firstName\":\"Dieťa\",\"lastName\":\"%s\",\"birthDate\":\"2025-01-01\"}"
            .formatted(last);
    MvcResult ok =
        send(
            receptionist(),
            "/api/reservations/with-stay",
            "{"
                + cardJson(
                    fx,
                    "2030-01-10",
                    "2030-01-12",
                    child,
                    ",\"bedId\":" + fx.extra() + ",\"extraBed\":true")
                + "}");
    assertThat(ok.getResponse().getStatus()).isEqualTo(201);
    assertThat(JsonPath.<String>read(json(ok), "$.stay.bedType")).isEqualTo("EXTRA");
  }

  @Test
  void withStayRespectsNightLimitAndRequiresPermissions() throws Exception {
    Fx fx = fx();
    long role =
        id("insert into role (code, name) values ('WS_LIMIT" + seq + "', 'Limit') returning id");
    jdbc.update(
        "insert into role_permission (role_id, permission_code, limit_value)"
            + " values (?, 'STAY_CREATE', 3)",
        role);
    jdbc.update(
        "insert into role_permission (role_id, permission_code) values (?, 'RESERVATION_EDIT')",
        role);
    long limitedUser = TestUsers.create(jdbc, encoder, "ws.limit" + seq, PASSWORD, false);
    jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", limitedUser, role);
    RequestPostProcessor limited = TestUsers.as(jdbc, "ws.limit" + seq);
    String last = "Limit" + System.nanoTime();

    MvcResult tooLong =
        send(
            limited,
            "/api/reservations/with-stay",
            "{"
                + cardJson(
                    fx,
                    "2030-01-10",
                    "2030-01-15",
                    newGuest("Ukážka", last),
                    ",\"bedId\":" + fx.bed1())
                + "}");
    assertThat(tooLong.getResponse().getStatus()).isEqualTo(422);
    assertThat(JsonPath.<String>read(json(tooLong), "$.code")).isEqualTo("MAX_NIGHTS_EXCEEDED");
    assertThat(cardsOf(last)).isZero();

    assertThat(
            send(
                    limited,
                    "/api/reservations/with-stay",
                    "{"
                        + cardJson(
                            fx,
                            "2030-01-10",
                            "2030-01-13",
                            newGuest("Ukážka", last),
                            ",\"bedId\":" + fx.bed1())
                        + "}")
                .getResponse()
                .getStatus())
        .isEqualTo(201);

    // Lekár nemá ani RESERVATION_EDIT, ani STAY_CREATE.
    assertThat(
            send(
                    user("ws.lekar", "DOCTOR"),
                    "/api/reservations/with-stay",
                    "{"
                        + cardJson(
                            fx,
                            "2030-01-20",
                            "2030-01-22",
                            newGuest("Ukážka", "Zakázaný"),
                            ",\"bedId\":" + fx.bed2())
                        + "}")
                .getResponse()
                .getStatus())
        .isEqualTo(403);
  }

  private long changeSeq(long reception) {
    List<Long> rows =
        jdbc.queryForList(
            "select seq from reception_change_seq where reception_id = ?", Long.class, reception);
    return rows.isEmpty() ? 0 : rows.get(0);
  }
}
