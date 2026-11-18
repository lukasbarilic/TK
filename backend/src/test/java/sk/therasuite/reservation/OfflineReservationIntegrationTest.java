package sk.therasuite.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.stream.IntStream;
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
import sk.therasuite.accommodation.SqlCounter;

/** Hromadný detail kariet pre offline režim. Fiktívne dáta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, SqlCounter.class})
class OfflineReservationIntegrationTest {

  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private record Fx(
      long receptionA,
      long receptionB,
      long crossing,
      long inside,
      long unassigned,
      long before,
      long otherReception,
      long client,
      long catA,
      long payer,
      long bedA) {}

  private long id(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private long card(String from, String to, long cat, long payer) {
    return id(
        "insert into reservation (reservation_number, status, arrival_date, departure_date,"
            + " booked_category_id, payer_id, adults) values (?, 'RESERVED', ?::date, ?::date, ?, ?, 1)"
            + " returning id",
        "OF" + System.nanoTime(),
        from,
        to,
        cat,
        payer);
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

  private long guest(long card, String last) {
    long client =
        id(
            "insert into client (first_name, last_name, email) values ('Jana', ?, 'jana@example.invalid') returning id",
            last);
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest) values (?, ?, true)",
        card,
        client);
    return client;
  }

  private Fx fx() {
    int n = ++seq;
    long centre =
        id("insert into centre (code, name) values ('OS" + n + "', 'Offline') returning id");
    long recA = id("insert into reception (code, name) values ('OA" + n + "', 'A') returning id");
    long recB = id("insert into reception (code, name) values ('OB" + n + "', 'B') returning id");
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
    long base = id("insert into base_category (name) values ('OK" + n + "') returning id");
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
            "insert into payer (code, name, type) values ('OP"
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
    long bedB =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR') returning id",
            roomB);

    long crossing = card("2032-05-28", "2032-06-05", catA, payer);
    stay(crossing, bedA, "2032-05-28", "2032-06-05");
    long client = guest(crossing, "Ukážková");
    long inside = card("2032-06-03", "2032-06-06", catA, payer);
    stay(inside, bedA, "2032-06-06", "2032-06-07");
    guest(inside, "Demová");
    long unassigned = card("2032-06-04", "2032-06-06", catA, payer);
    guest(unassigned, "Vymyslená");
    long before = card("2032-05-20", "2032-06-01", catA, payer);
    stay(before, bedA2, "2032-05-20", "2032-06-01");
    long other = card("2032-06-03", "2032-06-06", catB, payer);
    stay(other, bedB, "2032-06-03", "2032-06-06");
    guest(other, "Cudzia");
    return new Fx(
        recA, recB, crossing, inside, unassigned, before, other, client, catA, payer, bedA);
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

  private static String window(long reception) {
    return "/api/offline/reservations?receptionId=" + reception + "&from=2032-06-01&to=2032-06-10";
  }

  private List<Integer> ids(MvcResult r) throws Exception {
    return JsonPath.read(r.getResponse().getContentAsString(), "$[*].id");
  }

  @Test
  void windowIncludesStayCrossingBoundaryAndArrivalsOnly() throws Exception {
    Fx fx = fx();
    MvcResult r = mvc.perform(get(window(fx.receptionA())).with(user("of.a." + seq))).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(ids(r))
        .containsExactly((int) fx.crossing(), (int) fx.inside(), (int) fx.unassigned());
    String j = r.getResponse().getContentAsString();
    // rovnaký tvar ako GET /api/reservations/{id}
    MvcResult single =
        mvc.perform(get("/api/reservations/" + fx.crossing()).with(user("of.s." + seq)))
            .andReturn();
    assertThat(JsonPath.<Object>read(j, "$[0]"))
        .isEqualTo(JsonPath.<Object>read(single.getResponse().getContentAsString(), "$"));
    assertThat(JsonPath.<String>read(j, "$[0].guests[0].email")).isEqualTo("jana@example.invalid");
  }

  @Test
  void windowIsLimitedToReceptionScope() throws Exception {
    Fx fx = fx();
    var scoped = scopedUser("of.b." + seq, fx.receptionA());
    mvc.perform(get(window(fx.receptionB())).with(scoped))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    MvcResult r = mvc.perform(get(window(fx.receptionB())).with(user("of.c." + seq))).andReturn();
    assertThat(ids(r)).containsExactly((int) fx.otherReception());
  }

  @Test
  void idsReturnOnlyCardsInUserScope() throws Exception {
    Fx fx = fx();
    var scoped = scopedUser("of.d." + seq, fx.receptionA());
    String q = fx.crossing() + "," + fx.otherReception() + ",999999999";
    MvcResult r = mvc.perform(get("/api/offline/reservations?ids=" + q).with(scoped)).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(ids(r)).containsExactly((int) fx.crossing());
  }

  @Test
  void idsLimitIs200() throws Exception {
    Fx fx = fx();
    var who = user("of.e." + seq);
    String ok = String.join(",", IntStream.rangeClosed(1, 200).mapToObj(String::valueOf).toList());
    assertThat(
            mvc.perform(get("/api/offline/reservations?ids=" + ok).with(who))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(200);
    String tooMany = ok + ",201";
    assertThat(
            mvc.perform(get("/api/offline/reservations?ids=" + tooMany).with(who))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(
            mvc.perform(get("/api/offline/reservations").with(who))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(fx.crossing()).isPositive();
  }

  @Test
  void readIsAuditedWithContactScopeAndAllClients() throws Exception {
    Fx fx = fx();
    long before = id("select count(*) from audit_event where operation = 'RESERVATION_VIEW'");
    mvc.perform(get(window(fx.receptionA())).with(user("of.f." + seq))).andReturn();
    assertThat(id("select count(*) from audit_event where operation = 'RESERVATION_VIEW'"))
        .isEqualTo(before + 1);
    List<Long> audited =
        jdbc.queryForList(
            "select a.client_id from audit_subject_access a join audit_event e on e.id = a.audit_event_id"
                + " where e.operation = 'RESERVATION_VIEW' and a.data_scope = 'CONTACT'"
                + " and e.id = (select max(id) from audit_event where operation = 'RESERVATION_VIEW')",
            Long.class);
    assertThat(audited).hasSize(3).contains(fx.client());
  }

  @Test
  void windowSupports304UntilChange() throws Exception {
    Fx fx = fx();
    var who = user("of.g." + seq);
    String url = window(fx.receptionA());
    String etag = mvc.perform(get(url).with(who)).andReturn().getResponse().getHeader("ETag");
    assertThat(etag).isNotBlank();
    long audits = id("select count(*) from audit_event where operation = 'RESERVATION_VIEW'");
    MvcResult cached = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(cached.getResponse().getStatus()).isEqualTo(304);
    assertThat(id("select count(*) from audit_event where operation = 'RESERVATION_VIEW'"))
        .isEqualTo(audits);

    long extra = card("2032-06-02", "2032-06-03", fx.catA(), fx.payer());
    long bed2 =
        id(
            "select bd.id from bed bd join room rm on rm.id = bd.room_id"
                + " join building b on b.id = rm.building_id where b.reception_id = ? and bd.bed_number = 2",
            fx.receptionA());
    stay(extra, bed2, "2032-06-02", "2032-06-03");
    MvcResult changed = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(changed.getResponse().getStatus()).isEqualTo(200);
    assertThat(ids(changed)).contains((int) extra);
  }

  @Test
  void loadsWithoutNPlusOne() throws Exception {
    Fx fx = fx();
    var who = user("of.h." + seq);
    String one = "/api/offline/reservations?ids=" + fx.crossing();
    String many =
        "/api/offline/reservations?ids="
            + fx.crossing()
            + ","
            + fx.inside()
            + ","
            + fx.unassigned();
    mvc.perform(get(many).with(who)).andReturn(); // zahriatie
    int before = SqlCounter.STATEMENTS.size();
    mvc.perform(get(one).with(who)).andReturn();
    int oneCount = SqlCounter.STATEMENTS.size() - before;
    before = SqlCounter.STATEMENTS.size();
    mvc.perform(get(many).with(who)).andReturn();
    assertThat(SqlCounter.STATEMENTS.size() - before).isEqualTo(oneCount);
  }
}
