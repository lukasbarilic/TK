package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

/** Príchody a odchody recepcie: rozdelenie, rozsah používateľa, audit a ETag. Fiktívne dáta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ReceptionMovementsIntegrationTest {

  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private record Fx(
      long receptionA,
      long receptionB,
      long arriving,
      long departing,
      long unassigned,
      long otherReception,
      long cancelled,
      long client) {}

  private long id(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private long card(String status, String from, String to, long cat, long payer, int adults) {
    return id(
        "insert into reservation (reservation_number, status, arrival_date, departure_date,"
            + " booked_category_id, payer_id, adults) values (?, ?, ?::date, ?::date, ?, ?, ?)"
            + " returning id",
        "MV" + System.nanoTime(),
        status,
        from,
        to,
        cat,
        payer,
        adults);
  }

  private void stay(long card, long bed, String from, String to) {
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " select ?, ?, ?::date, ?::date, booked_category_id from reservation where id = ?",
        card,
        bed,
        from,
        to,
        card);
  }

  private Fx fx() {
    int n = ++seq;
    long centre =
        id("insert into centre (code, name) values ('MS" + n + "', 'Pohyby') returning id");
    long recA = id("insert into reception (code, name) values ('MA" + n + "', 'A') returning id");
    long recB = id("insert into reception (code, name) values ('MB" + n + "', 'B') returning id");
    long buA =
        id(
            "insert into building (centre_id, reception_id, code, name) values (?, ?, 'A', 'Budova A') returning id",
            centre,
            recA);
    long buB =
        id(
            "insert into building (centre_id, reception_id, code, name) values (?, ?, 'B', 'Budova B') returning id",
            centre,
            recB);
    long base = id("insert into base_category (name) values ('MK" + n + "') returning id");
    long catA =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'KA', 'Kategória A', 2) returning id",
            centre,
            base);
    long catB =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'KB', 'Kategória B', 2) returning id",
            centre,
            base);
    long payer =
        id(
            "insert into payer (code, name, type) values ('MP"
                + n
                + "', 'Platca', 'SELF') returning id");
    long roomA =
        id(
            "insert into room (building_id, number, category_id, regular_beds) values (?, '101', ?, 3) returning id",
            buA,
            catA);
    long roomB =
        id(
            "insert into room (building_id, number, category_id, regular_beds) values (?, '201', ?, 2) returning id",
            buB,
            catB);
    long bedA =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR') returning id",
            roomA);
    long bedA2 =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 2, 'REGULAR') returning id",
            roomA);
    id("insert into bed (room_id, bed_number, type) values (?, 3, 'REGULAR') returning id", roomA);
    long bedB =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR') returning id",
            roomB);

    long client =
        id(
            "insert into client (first_name, last_name, birth_date) values ('Jana', 'Ukážková', '1980-05-17') returning id");
    long arriving = card("RESERVED", "2031-03-10", "2031-03-17", catA, payer, 2);
    stay(arriving, bedA, "2031-03-10", "2031-03-17");
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest) values (?, ?, true)",
        arriving,
        client);
    long departing = card("CHECKED_IN", "2031-03-03", "2031-03-10", catA, payer, 1);
    stay(departing, bedA, "2031-03-03", "2031-03-10");
    // neubytovaná karta s príchodom: recepcia podľa kategórie A
    long unassigned = card("RESERVED", "2031-03-10", "2031-03-12", catA, payer, 1);
    // karta v budove inej recepcie
    long other = card("RESERVED", "2031-03-10", "2031-03-12", catB, payer, 1);
    stay(other, bedB, "2031-03-10", "2031-03-12");
    long cancelled = card("CANCELLED", "2031-03-10", "2031-03-12", catA, payer, 1);
    stay(cancelled, bedA2, "2031-03-10", "2031-03-12");
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, '2031-03-10', 'OPEN')",
        recA);
    return new Fx(recA, recB, arriving, departing, unassigned, other, cancelled, client);
  }

  private static Object first(String json, String path) {
    return JsonPath.<List<Object>>read(json, path).get(0);
  }

  private RequestPostProcessor user(String name) {
    TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false, "RECEPTIONIST");
    return TestUsers.as(jdbc, name);
  }

  private RequestPostProcessor scopedUser(String name, long receptionId) {
    TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id)"
            + " select u.id, r.id, ? from user_account u, role r where u.username = ? and r.code = 'RECEPTIONIST'",
        receptionId,
        name);
    return TestUsers.as(jdbc, name);
  }

  @Test
  void splitsArrivalsAndDepartures() throws Exception {
    Fx fx = fx();
    long audits =
        jdbc.queryForObject(
            "select count(*) from audit_event where operation = 'RECEPTION_MOVEMENTS'", Long.class);

    MvcResult r =
        mvc.perform(
                get("/api/receptions/" + fx.receptionA() + "/movements?date=2031-03-10")
                    .with(user("mv.a." + seq)))
            .andReturn();

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    String j = r.getResponse().getContentAsString();
    assertThat(JsonPath.<String>read(j, "$.date")).isEqualTo("2031-03-10");
    assertThat(JsonPath.<List<Integer>>read(j, "$.arrivals[*].reservationId"))
        .containsExactlyInAnyOrder((int) fx.arriving(), (int) fx.unassigned());
    assertThat(JsonPath.<List<Integer>>read(j, "$.departures[*].reservationId"))
        .containsExactly((int) fx.departing());
    assertThat(first(j, "$.arrivals[?(@.reservationId==" + fx.arriving() + ")].guestName"))
        .isEqualTo("Ukážková J.");
    assertThat(first(j, "$.arrivals[?(@.reservationId==" + fx.arriving() + ")].birthYear"))
        .isEqualTo(1980);
    assertThat(first(j, "$.arrivals[?(@.reservationId==" + fx.arriving() + ")].guestCount"))
        .isEqualTo(2);
    assertThat(first(j, "$.arrivals[?(@.reservationId==" + fx.arriving() + ")].roomNumber"))
        .isEqualTo("101");
    assertThat(first(j, "$.arrivals[?(@.reservationId==" + fx.unassigned() + ")].roomNumber"))
        .isNull();
    assertThat(JsonPath.<String>read(j, "$.arrivals[0].payer.name")).isEqualTo("Platca");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'RECEPTION_MOVEMENTS'",
                Long.class))
        .isEqualTo(audits + 1);
    assertThat(
            jdbc.queryForList(
                "select a.client_id from audit_subject_access a join audit_event e on e.id = a.audit_event_id"
                    + " where e.operation = 'RECEPTION_MOVEMENTS' and a.data_scope = 'NAME'"
                    + " and a.client_id = ?",
                Long.class,
                fx.client()))
        .isNotEmpty();
  }

  @Test
  void otherReceptionSeesOwnMovementsOnly() throws Exception {
    Fx fx = fx();
    MvcResult r =
        mvc.perform(
                get("/api/receptions/" + fx.receptionB() + "/movements?date=2031-03-10")
                    .with(user("mv.b." + seq)))
            .andReturn();
    assertThat(
            JsonPath.<List<Integer>>read(
                r.getResponse().getContentAsString(), "$.arrivals[*].reservationId"))
        .containsExactly((int) fx.otherReception());
  }

  @Test
  void defaultsToBusinessDate() throws Exception {
    Fx fx = fx();
    MvcResult r =
        mvc.perform(
                get("/api/receptions/" + fx.receptionA() + "/movements").with(user("mv.c." + seq)))
            .andReturn();
    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.date"))
        .isEqualTo("2031-03-10");
  }

  @Test
  void userOfOtherReceptionGets403() throws Exception {
    Fx fx = fx();
    var scoped = scopedUser("mv.d." + seq, fx.receptionA());
    mvc.perform(
            get("/api/receptions/" + fx.receptionB() + "/movements?date=2031-03-10").with(scoped))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/receptions/" + fx.receptionA() + "/movements?date=2031-03-10").with(scoped))
        .andExpect(status().isOk());
  }

  @Test
  void returns304UntilDataChange() throws Exception {
    Fx fx = fx();
    var who = user("mv.e." + seq);
    String url = "/api/receptions/" + fx.receptionA() + "/movements?date=2031-03-10";
    String etag = mvc.perform(get(url).with(who)).andReturn().getResponse().getHeader("ETag");
    assertThat(etag).isNotBlank();
    long audits =
        jdbc.queryForObject(
            "select count(*) from audit_event where operation = 'RECEPTION_MOVEMENTS'", Long.class);

    MvcResult cached = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(cached.getResponse().getStatus()).isEqualTo(304);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'RECEPTION_MOVEMENTS'",
                Long.class))
        .isEqualTo(audits);

    long bed =
        id(
            "select bd.id from bed bd join room rm on rm.id = bd.room_id"
                + " join building b on b.id = rm.building_id where b.reception_id = ? and bd.bed_number = 3",
            fx.receptionA());
    long extra =
        card(
            "RESERVED",
            "2031-03-10",
            "2031-03-11",
            id("select booked_category_id from reservation where id = ?", fx.arriving()),
            id("select payer_id from reservation where id = ?", fx.arriving()),
            1);
    stay(extra, bed, "2031-03-10", "2031-03-11");

    MvcResult changed = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(changed.getResponse().getStatus()).isEqualTo(200);
    assertThat(
            JsonPath.<List<Integer>>read(
                changed.getResponse().getContentAsString(), "$.arrivals[*].reservationId"))
        .contains((int) extra);
  }
}
