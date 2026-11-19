package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
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
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ReservationIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private static long categoryId;
  private static long payerId;
  private static boolean seeded;

  @BeforeAll
  static void resetSeed() {
    seeded = false;
  }

  private void seed() {
    if (seeded) {
      return;
    }
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values ('RT', 'Test centrum') returning id",
            Long.class);
    long base =
        jdbc.queryForObject(
            "insert into base_category (name) values ('Rezervácie test') returning id", Long.class);
    categoryId =
        jdbc.queryForObject(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'RT1', 'Dvojlôžková test', 2) returning id",
            Long.class,
            centre,
            base);
    payerId =
        jdbc.queryForObject(
            "insert into payer (code, name, type) values ('RTP', 'Samoplatca test', 'SELF')"
                + " returning id",
            Long.class);
    seeded = true;
  }

  private RequestPostProcessor as(String username, String role) {
    seed();
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, role);
    }
    return TestUsers.as(jdbc, username);
  }

  private RequestPostProcessor receptionist() {
    return as("rez.recepcna", "RECEPTIONIST");
  }

  private String createBody(String arrival, String departure, int adults, String guests) {
    seed();
    return """
    {"arrivalDate":"%s","departureDate":"%s","bookedCategoryId":%d,"payerId":%d,
     "adults":%d,"children":0,"exclusiveRoomUse":false,"guests":[%s]}
    """
        .formatted(arrival, departure, categoryId, payerId, adults, guests);
  }

  private static String newGuest(String first, String last) {
    return "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"birthDate\":\"1970-01-01\",\"nationality\":\"SK\",\"email\":\"%s@example.invalid\",\"phone\":\"+000 000 000\"}"
        .formatted(first, last, first.toLowerCase());
  }

  private MvcResult create(String body) throws Exception {
    return mvc.perform(
            post("/api/reservations")
                .with(csrf())
                .with(receptionist())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private MvcResult createCard(String first, String last) throws Exception {
    MvcResult result = create(createBody("2030-05-01", "2030-05-11", 1, newGuest(first, last)));
    assertThat(result.getResponse().getStatus()).isEqualTo(201);
    return result;
  }

  @Test
  void createGeneratesNumberDefaultsAndAudit() throws Exception {
    MvcResult result = createCard("Adam", "Testovský");
    String json = result.getResponse().getContentAsString();

    assertThat(JsonPath.<String>read(json, "$.reservationNumber")).matches("R\\d+");
    assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("RESERVED");
    assertThat(JsonPath.<String>read(json, "$.priceCategoryMode")).isEqualTo("FOLLOW_ROOM");
    assertThat(JsonPath.<String>read(json, "$.priceType")).isEqualTo("PER_BED");
    assertThat(JsonPath.<String>read(json, "$.guests[0].lastName")).isEqualTo("Testovský");
    assertThat(JsonPath.<Boolean>read(json, "$.guests[0].primaryGuest")).isTrue();

    String eventId = result.getResponse().getHeader("X-Audit-Event-Id");
    assertThat(eventId).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select operation from audit_event where id = ?",
                String.class,
                Long.valueOf(eventId)))
        .isEqualTo("RESERVATION_CREATE");
    List<String> entities =
        jdbc.queryForList(
            "select entity_type from audit_change where audit_event_id = ?",
            String.class,
            Long.valueOf(eventId));
    assertThat(entities).contains("reservation", "client", "reservation_guest");
  }

  @Test
  void createWithExistingClientReusesClient() throws Exception {
    MvcResult first = createCard("Eva", "Existujúca");
    long clientId =
        ((Number) JsonPath.read(first.getResponse().getContentAsString(), "$.guests[0].clientId"))
            .longValue();

    MvcResult second =
        create(createBody("2030-06-01", "2030-06-08", 1, "{\"clientId\":" + clientId + "}"));

    assertThat(second.getResponse().getStatus()).isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from client where last_name = 'Existujúca'", Long.class))
        .isEqualTo(1);
  }

  @Test
  void createValidatesInput() throws Exception {
    String guest = newGuest("Valid", "Validný");
    // príchod nie je pred odchodom
    assertThat(create(createBody("2030-05-10", "2030-05-10", 1, guest)).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(create(createBody("2030-05-10", "2030-05-01", 1, guest)).getResponse().getStatus())
        .isEqualTo(400);
    // viac ako 365 nocí
    assertThat(create(createBody("2030-01-01", "2031-01-02", 1, guest)).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(create(createBody("2030-01-01", "2031-01-01", 1, guest)).getResponse().getStatus())
        .isEqualTo(201);
    // dospelí < 1
    assertThat(create(createBody("2030-05-01", "2030-05-02", 0, guest)).getResponse().getStatus())
        .isEqualTo(400);
    // bez hostí, neexistujúci klient, nový klient bez mena
    assertThat(create(createBody("2030-05-01", "2030-05-02", 1, "")).getResponse().getStatus())
        .isEqualTo(400);
    assertThat(
            create(createBody("2030-05-01", "2030-05-02", 1, "{\"clientId\":999999999}"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            create(createBody("2030-05-01", "2030-05-02", 1, "{\"firstName\":\"Bez\"}"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    // neexistujúca kategória
    assertThat(
            create(
                    createBody("2030-05-01", "2030-05-02", 1, guest)
                        .replace(
                            "\"bookedCategoryId\":" + categoryId, "\"bookedCategoryId\":999999999"))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
  }

  @Test
  void databaseRejectsInvalidRows() {
    seed();
    for (String[] dates :
        new String[][] {
          {"2030-05-10", "2030-05-10", "1"},
          {"2030-01-01", "2031-01-02", "1"},
          {"2030-05-01", "2030-05-02", "0"}
        }) {
      assertThatThrownBy(() ->
              jdbc.update(
                  "insert into reservation (reservation_number, arrival_date, departure_date,"
                      + " booked_category_id, payer_id, adults) values ('X' || random(), ?::date, ?::date, ?, ?, ?)",
                  dates[0],
                  dates[1],
                  categoryId,
                  payerId,
                  Integer.valueOf(dates[2])))
          .isInstanceOf(DataAccessException.class);
    }
  }

  @Test
  void detailContainsContactListDoesNot() throws Exception {
    MvcResult created = createCard("Detail", "Kontaktný");
    long id =
        ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

    mvc.perform(get("/api/reservations/" + id).with(receptionist()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.guests[0].email").value("detail@example.invalid"))
        .andExpect(jsonPath("$.guests[0].phone").value("+000 000 000"));

    mvc.perform(get("/api/reservations").param("q", "Kontaktný").with(receptionist()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].guestLastName").value("Kontaktný"))
        .andExpect(jsonPath("$.content[0].guestBirthYear").value(1970))
        .andExpect(jsonPath("$.content[0].bookedCategoryName").value("Dvojlôžková test"))
        .andExpect(jsonPath("$.content[0].email").doesNotExist())
        .andExpect(jsonPath("$.content[0].phone").doesNotExist())
        .andExpect(jsonPath("$.content[0].guests").doesNotExist());

    mvc.perform(get("/api/reservations/999999999").with(receptionist()))
        .andExpect(status().isNotFound());
  }

  @Test
  void searchWorksWithAndWithoutDiacriticsAndByNumber() throws Exception {
    MvcResult created = createCard("Žofia", "Číháková");
    String number =
        JsonPath.read(created.getResponse().getContentAsString(), "$.reservationNumber");

    for (String q : List.of("Číháková", "cihakova", "CIHAK", "zofia cihakova", "číhákov žof")) {
      mvc.perform(get("/api/reservations").param("q", q).with(receptionist()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.content[*].reservationNumber", hasItem(number)));
    }
    mvc.perform(get("/api/reservations").param("q", number.toLowerCase()).with(receptionist()))
        .andExpect(jsonPath("$.content[*].reservationNumber", hasItem(number)));
    mvc.perform(get("/api/reservations").param("q", "Nikto Taký").with(receptionist()))
        .andExpect(jsonPath("$.totalElements").value(0));
    // špeciálne znaky LIKE sa berú doslovne
    mvc.perform(get("/api/reservations").param("q", "%").with(receptionist()))
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void searchFiltersByStatusDatesAndPages() throws Exception {
    createCard("Filter", "Stránkový");
    createCard("Filter", "Stránkový");
    RequestPostProcessor user = receptionist();

    mvc.perform(
            get("/api/reservations")
                .param("q", "Stránkový")
                .param("size", "1")
                .param("page", "1")
                .with(user))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(
            get("/api/reservations")
                .param("q", "Stránkový")
                .param("status", "CANCELLED")
                .with(user))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/reservations")
                .param("q", "Stránkový")
                .param("from", "2030-05-11")
                .param("to", "2030-05-20")
                .with(user))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/reservations")
                .param("q", "Stránkový")
                .param("from", "2030-05-10")
                .param("to", "2030-05-20")
                .with(user))
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(get("/api/reservations").param("size", "1000").with(user))
        .andExpect(status().isBadRequest());
  }

  @Test
  void withoutStayReturnsOnlyCardsWithoutStayAndTotalMatches() throws Exception {
    MvcResult withStay = createCard("Pobytová", "Bezpobytový");
    MvcResult without = createCard("Voľná", "Bezpobytový");
    long withStayId =
        ((Number) JsonPath.read(withStay.getResponse().getContentAsString(), "$.id")).longValue();
    String withoutNumber =
        JsonPath.read(without.getResponse().getContentAsString(), "$.reservationNumber");
    long centre = jdbc.queryForObject("select id from centre where code = 'RT'", Long.class);
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values ('RTWS', 'Test') returning id", Long.class);
    long building =
        jdbc.queryForObject(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            Long.class,
            centre,
            reception);
    long room =
        jdbc.queryForObject(
            "insert into room (building_id, number, category_id, regular_beds, extra_beds)"
                + " values (?, '1', ?, 1, 0) returning id",
            Long.class,
            building,
            categoryId);
    long bed =
        jdbc.queryForObject(
            "insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR') returning id",
            Long.class,
            room);
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, '2030-05-10', '2030-05-12', ?)",
        withStayId,
        bed,
        categoryId);
    RequestPostProcessor user = receptionist();

    mvc.perform(get("/api/reservations").param("q", "Bezpobytový").with(user))
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(
            get("/api/reservations")
                .param("q", "Bezpobytový")
                .param("withoutStay", "true")
                .with(user))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].reservationNumber").value(withoutNumber));
    mvc.perform(
            get("/api/reservations")
                .param("q", "Bezpobytový")
                .param("withoutStay", "false")
                .with(user))
        .andExpect(jsonPath("$.totalElements").value(2));
  }

  private MvcResult patchCard(long id, String body) throws Exception {
    return mvc.perform(
            patch("/api/reservations/" + id)
                .with(csrf())
                .with(receptionist())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  @Test
  void patchUpdatesFieldsIncrementsVersionAndAudits() throws Exception {
    MvcResult created = createCard("Zmena", "Upravený");
    String json = created.getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(json, "$.id")).longValue();
    int version = JsonPath.read(json, "$.version");

    MvcResult patched =
        patchCard(
            id,
            "{\"version\":%d,\"arrivalDate\":\"2030-05-02\",\"doNotMove\":true,\"earlyCheckIn\":true,\"lateCheckOut\":true,\"exclusiveRoomUse\":true}"
                .formatted(version));

    assertThat(patched.getResponse().getStatus()).isEqualTo(200);
    String out = patched.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(out, "$.arrivalDate")).isEqualTo("2030-05-02");
    assertThat(JsonPath.<String>read(out, "$.departureDate")).isEqualTo("2030-05-11");
    assertThat(JsonPath.<Boolean>read(out, "$.doNotMove")).isTrue();
    assertThat(JsonPath.<Boolean>read(out, "$.exclusiveRoomUse")).isTrue();
    assertThat((int) JsonPath.read(out, "$.version")).isGreaterThan(version);

    String eventId = patched.getResponse().getHeader("X-Audit-Event-Id");
    Map<String, Object> change =
        jdbc.queryForMap(
            "select change_type, changed_fields::text f from audit_change"
                + " where audit_event_id = ? and entity_type = 'reservation'",
            Long.valueOf(eventId));
    assertThat(change).containsEntry("change_type", "UPDATE");
    assertThat(change.get("f").toString()).contains("arrivalDate", "doNotMove");
    assertThat(
            jdbc.queryForObject(
                "select operation from audit_event where id = ?",
                String.class,
                Long.valueOf(eventId)))
        .isEqualTo("RESERVATION_UPDATE");
  }

  @Test
  void patchWithStaleVersionReturns409AndKeepsData() throws Exception {
    MvcResult created = createCard("Konflikt", "Verzie");
    String json = created.getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(json, "$.id")).longValue();
    int version = JsonPath.read(json, "$.version");

    assertThat(
            patchCard(id, "{\"version\":%d,\"doNotMove\":true}".formatted(version))
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    MvcResult stale = patchCard(id, "{\"version\":%d,\"lateCheckOut\":true}".formatted(version));

    assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    assertThat(stale.getResponse().getContentAsString()).contains("\"code\":\"CONFLICT\"");
    assertThat(
            jdbc.queryForObject(
                "select late_check_out from reservation where id = ?", Boolean.class, id))
        .isFalse();
  }

  @Test
  void patchValidatesDatesAndRequiresVersion() throws Exception {
    MvcResult created = createCard("Neplatný", "Termín");
    String json = created.getResponse().getContentAsString();
    long id = ((Number) JsonPath.read(json, "$.id")).longValue();
    int version = JsonPath.read(json, "$.version");

    assertThat(
            patchCard(id, "{\"version\":%d,\"departureDate\":\"2030-05-01\"}".formatted(version))
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(patchCard(id, "{\"doNotMove\":true}").getResponse().getStatus()).isEqualTo(400);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'RESERVATION_UPDATE'"
                    + " and outcome = 'REJECTED'",
                Long.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void permissionsAreEnforced() throws Exception {
    MvcResult created = createCard("Práva", "Oprávnený");
    long id =
        ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
    RequestPostProcessor doctor = as("rez.lekar", "DOCTOR");
    RequestPostProcessor auditor = as("rez.auditor", "AUDITOR");

    mvc.perform(get("/api/reservations").with(doctor)).andExpect(status().isForbidden());
    mvc.perform(get("/api/reservations/" + id).with(auditor)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/reservations")
                .with(csrf())
                .with(doctor)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("2030-05-01", "2030-05-02", 1, newGuest("Zakázaný", "Zápis"))))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch("/api/reservations/" + id)
                .with(csrf())
                .with(auditor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"doNotMove\":true}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/reservations")).andExpect(status().is4xxClientError());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from client where last_name = 'Zápis'", Long.class))
        .isZero();
  }

  @Test
  void writeResponseCarriesAuditHeader() throws Exception {
    mvc.perform(
            post("/api/reservations")
                .with(csrf())
                .with(receptionist())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    createBody("2030-07-01", "2030-07-05", 2, newGuest("Hlavička", "Auditná"))))
        .andExpect(status().isCreated())
        .andExpect(header().exists("X-Audit-Event-Id"));
  }
}
