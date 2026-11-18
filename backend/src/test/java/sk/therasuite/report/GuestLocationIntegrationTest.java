package sk.therasuite.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
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

/** Zoznam hostí s miestom ubytovania: výber, oprávnenia, CSV, audit, ETag. Fiktívne dáta. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class GuestLocationIntegrationTest {

  private static final String DAY = "2032-05-10";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private record Fx(
      long centre, long doctorA, long doctorB, long own, long other, long moved, long cancelled) {}

  private long id(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private long doctor(String name) {
    return TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false, "DOCTOR");
  }

  private long card(String status, String from, String to, long cat, long payer, Long doctor) {
    return id(
        "insert into reservation (reservation_number, status, arrival_date, departure_date,"
            + " booked_category_id, payer_id, attending_doctor_id) values (?, ?, ?::date, ?::date, ?, ?, ?)"
            + " returning id",
        "GL" + System.nanoTime(),
        status,
        from,
        to,
        cat,
        payer,
        doctor);
  }

  private void guest(long card, String first, String last) {
    long client =
        id(
            "insert into client (first_name, last_name, birth_date) values (?, ?, '1975-02-03') returning id",
            first,
            last);
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest) values (?, ?, true)",
        card,
        client);
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
        id("insert into centre (code, name) values ('GL" + n + "', 'Hostia') returning id");
    long rec = id("insert into reception (code, name) values ('GR" + n + "', 'R') returning id");
    long bu =
        id(
            "insert into building (centre_id, reception_id, code, name) values (?, ?, 'A', 'Budova A') returning id",
            centre,
            rec);
    long base = id("insert into base_category (name) values ('GK" + n + "') returning id");
    long cat =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'KA', 'Kategória', 2) returning id",
            centre,
            base);
    long payer =
        id(
            "insert into payer (code, name, type) values ('GP"
                + n
                + "', 'Platca', 'SELF') returning id");
    long room1 =
        id(
            "insert into room (building_id, number, floor, category_id, regular_beds) values (?, '101', '1', ?, 2) returning id",
            bu,
            cat);
    long room2 =
        id(
            "insert into room (building_id, number, floor, category_id, regular_beds) values (?, '202', '2', ?, 2) returning id",
            bu,
            cat);
    long bed1 =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR') returning id",
            room1);
    long bed1b =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 2, 'REGULAR') returning id",
            room1);
    long bed2 =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR') returning id",
            room2);
    long bed3 =
        id(
            "insert into bed (room_id, bed_number, type) values (?, 2, 'REGULAR') returning id",
            room2);
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, ?::date, 'OPEN')",
        rec,
        DAY);

    long docA = doctor("gl.docA." + n);
    long docB = doctor("gl.docB." + n);
    long own = card("CHECKED_IN", "2032-05-08", "2032-05-14", cat, payer, docA);
    guest(own, "Jana", "Ukážková");
    stay(own, bed1, "2032-05-08", "2032-05-14");
    long other = card("CHECKED_IN", "2032-05-08", "2032-05-14", cat, payer, docB);
    guest(other, "Peter", "Vymyslený");
    stay(other, bed2, "2032-05-08", "2032-05-14");
    // zmena izby počas dňa: izba 101 do DAY, izba 202 od DAY
    long moved = card("CHECKED_IN", "2032-05-07", "2032-05-13", cat, payer, docA);
    guest(moved, "Eva", "Presunutá");
    stay(moved, bed1b, "2032-05-07", DAY);
    stay(moved, bed3, DAY, "2032-05-13");
    long cancelled = card("CANCELLED", "2032-05-08", "2032-05-14", cat, payer, docA);
    guest(cancelled, "Zrušená", "Karta");
    return new Fx(centre, docA, docB, own, other, moved, cancelled);
  }

  private RequestPostProcessor as(long userId) {
    String name =
        jdbc.queryForObject("select username from user_account where id = ?", String.class, userId);
    return TestUsers.as(jdbc, name);
  }

  private RequestPostProcessor receptionist(String name) {
    TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false, "RECEPTIONIST");
    return TestUsers.as(jdbc, name);
  }

  private RequestPostProcessor manager(String name, Long defaultReception) {
    long uid = TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false, "RECEPTION_MANAGER");
    jdbc.update(
        "update user_account set default_reception_id = ? where id = ?", defaultReception, uid);
    return TestUsers.as(jdbc, name);
  }

  private static List<Integer> ids(MvcResult r) throws Exception {
    return JsonPath.read(r.getResponse().getContentAsString(), "$.guests[*].reservationId");
  }

  private long auditCount(String op) {
    return id("select count(*) from audit_event where operation = ?", op);
  }

  @Test
  void doctorSeesOnlyOwnPatients() throws Exception {
    Fx fx = fx();
    long before = auditCount("GUEST_LOCATION_VIEW");
    MvcResult r =
        mvc.perform(get("/api/reports/guest-locations?date=" + DAY).with(as(fx.doctorA())))
            .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(ids(r)).contains((int) fx.own(), (int) fx.moved());
    assertThat(ids(r)).doesNotContain((int) fx.other(), (int) fx.cancelled());
    assertThat(auditCount("GUEST_LOCATION_VIEW")).isEqualTo(before + 1);
    assertThat(
            jdbc.queryForList(
                "select a.data_scope from audit_subject_access a join audit_event e on e.id = a.audit_event_id"
                    + " where e.operation = 'GUEST_LOCATION_VIEW' order by e.id desc limit 1",
                String.class))
        .containsExactly("NAME");
  }

  @Test
  void doctorCannotAskForAnotherDoctor() throws Exception {
    Fx fx = fx();
    mvc.perform(
            get("/api/reports/guest-locations?date=" + DAY + "&doctorId=" + fx.doctorB())
                .with(as(fx.doctorA())))
        .andExpect(status().isForbidden());
  }

  @Test
  void viewAllSeesEveryoneAndFiltersByDoctor() throws Exception {
    Fx fx = fx();
    var who = receptionist("gl.rec." + seq);
    MvcResult all =
        mvc.perform(
                get("/api/reports/guest-locations?date=" + DAY + "&centreId=" + fx.centre())
                    .with(who))
            .andReturn();
    assertThat(ids(all)).contains((int) fx.own(), (int) fx.other(), (int) fx.moved());
    MvcResult b =
        mvc.perform(
                get("/api/reports/guest-locations?date=" + DAY + "&doctorId=" + fx.doctorB())
                    .with(who))
            .andReturn();
    assertThat(ids(b)).containsExactly((int) fx.other());
  }

  @Test
  void movedGuestHasTwoRows() throws Exception {
    Fx fx = fx();
    MvcResult r =
        mvc.perform(get("/api/reports/guest-locations?date=" + DAY).with(as(fx.doctorA())))
            .andReturn();
    String j = r.getResponse().getContentAsString();
    List<String> rooms = JsonPath.read(j, "$.guests[?(@.reservationId==" + fx.moved() + ")].room");
    assertThat(rooms).containsExactly("101", "202");
    List<String> movedOn =
        JsonPath.read(j, "$.guests[?(@.reservationId==" + fx.moved() + ")].movedOn");
    assertThat(movedOn).containsExactly(DAY, DAY);
    List<String> ownMoved =
        JsonPath.read(j, "$.guests[?(@.reservationId==" + fx.own() + ")].movedOn");
    assertThat(ownMoved).hasSize(1).containsNull();
  }

  @Test
  void csvWithoutExportIs403() throws Exception {
    Fx fx = fx();
    mvc.perform(
            get("/api/reports/guest-locations?format=csv&date=" + DAY)
                .with(receptionist("gl.rec2." + seq)))
        .andExpect(status().isForbidden());
  }

  @Test
  void csvHasBomSemicolonAndAudit() throws Exception {
    Fx fx = fx();
    long before = auditCount("GUEST_LOCATION_EXPORT");
    MvcResult r =
        mvc.perform(
                get("/api/reports/guest-locations?format=csv&date=" + DAY).with(as(fx.doctorA())))
            .andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(r.getResponse().getContentType()).startsWith("text/csv");
    byte[] body = r.getResponse().getContentAsByteArray();
    assertThat(body).startsWith(0xEF, 0xBB, 0xBF);
    String text = new String(body, StandardCharsets.UTF_8);
    String[] lines = text.substring(1).split("\r\n");
    assertThat(lines[0]).startsWith("Priezvisko a meno;Rok narodenia;Budova;");
    assertThat(text).contains("Ukážková Jana;1975;Budova A;1;101;1;REGULAR;");
    assertThat(lines).hasSize(4); // hlavička + vlastný pacient + dva riadky presunutého
    assertThat(auditCount("GUEST_LOCATION_EXPORT")).isEqualTo(before + 1);
  }

  @Test
  void defaultsToBusinessDayOfMainReception() throws Exception {
    Fx fx = fx();
    long rec = id("select reception_id from building where centre_id = ?", fx.centre());
    var who = manager("gl.mgr." + seq, rec);
    MvcResult r = mvc.perform(get("/api/reports/guest-locations").with(who)).andReturn();
    assertThat(JsonPath.<String>read(r.getResponse().getContentAsString(), "$.date"))
        .isEqualTo(DAY);
    mvc.perform(get("/api/reports/guest-locations").with(manager("gl.mgr2." + seq, null)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void returns304UntilDataChange() throws Exception {
    Fx fx = fx();
    var who = as(fx.doctorA());
    String url = "/api/reports/guest-locations?date=" + DAY;
    String etag = mvc.perform(get(url).with(who)).andReturn().getResponse().getHeader("ETag");
    assertThat(etag).isNotBlank();
    long before = auditCount("GUEST_LOCATION_VIEW");
    assertThat(
            mvc.perform(get(url).with(who).header("If-None-Match", etag))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(304);
    assertThat(auditCount("GUEST_LOCATION_VIEW")).isEqualTo(before);

    jdbc.update(
        "update reservation set attending_doctor_id = ? where id = ?", fx.doctorA(), fx.other());
    MvcResult changed = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(changed.getResponse().getStatus()).isEqualTo(200);
    assertThat(ids(changed)).contains((int) fx.other());
  }

  @Test
  void unsupportedFormatIs400() throws Exception {
    Fx fx = fx();
    mvc.perform(get("/api/reports/guest-locations?format=pdf&date=" + DAY).with(as(fx.doctorA())))
        .andExpect(status().isBadRequest());
  }
}
