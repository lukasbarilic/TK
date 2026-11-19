package sk.therasuite.businessday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.businessday.application.BusinessDayReport;
import sk.therasuite.businessday.application.BusinessDayReportService;

/** Report dňa: osoby, obsadenosť s blokáciou a prístelkou, výhľad, archív a stav NO_SHOW. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class BusinessDayReportIntegrationTest {

  private static final String DAY = "2090-03-10";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired BusinessDayReportService reports;

  private static int seq;
  private final java.util.List<Long> created = new java.util.ArrayList<>();

  /** Karty bez pobytu by blokovali uzávierky ostatných testov, preto sa po teste zmažú. */
  @org.junit.jupiter.api.AfterEach
  void cleanCards() {
    created.forEach(id -> {
      jdbc.update("delete from stay where reservation_id = ?", id);
      jdbc.update("delete from reservation where id = ?", id);
    });
    created.clear();
  }

  /** Beds: izba A (2 bežné, blokovaná), izba B (1 bežné + 1 prístelka), izba C (2 bežné). */
  private record Setup(long reception, long bedB1, long bedB2, long category, long payer) {}

  private Setup setup() {
    int n = ++seq;
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values (?, 'Test') returning id",
            Long.class,
            "RP" + n);
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values (?, 'Test recepcia') returning id",
            Long.class,
            "RPR" + n);
    long building =
        jdbc.queryForObject(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            Long.class,
            centre,
            reception);
    long base =
        jdbc.queryForObject(
            "insert into base_category (name) values (?) returning id", Long.class, "RPK" + n);
    long category =
        jdbc.queryForObject(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'K', 'Kat', 2) returning id",
            Long.class,
            centre,
            base);
    long roomA = room(building, category, "A", 2, 0);
    long roomC = room(building, category, "C", 2, 0);
    bed(roomA, 1, "REGULAR");
    bed(roomA, 2, "REGULAR");
    // izba s prístelkou sa kontroluje po commite, preto izba aj lôžka idú jedným príkazom
    var roomB =
        jdbc.queryForMap(
            "with r as (insert into room (building_id, number, category_id, regular_beds,"
                + " extra_beds) values (?, 'B', ?, 1, 1) returning id),"
                + " regular as (insert into bed (room_id, bed_number, type)"
                + "   select id, 1, 'REGULAR' from r returning id),"
                + " extra as (insert into bed (room_id, bed_number, type)"
                + "   select id, 2, 'EXTRA' from r returning id)"
                + " select (select id from regular) as b1, (select id from extra) as b2",
            building,
            category);
    long b1 = ((Number) roomB.get("b1")).longValue();
    long b2 = ((Number) roomB.get("b2")).longValue();
    bed(roomC, 1, "REGULAR");
    bed(roomC, 2, "REGULAR");
    long reason =
        jdbc.queryForObject(
            "insert into block_reason (name) values (?) returning id", Long.class, "Oprava RP" + n);
    jdbc.update(
        "insert into room_block (room_id, reason_id, date_from, date_to)"
            + " values (?, ?, ?::date, ?::date)",
        roomA,
        reason,
        DAY,
        "2090-03-12");
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, ?::date,"
            + " 'OPEN')",
        reception,
        DAY);
    long payer =
        jdbc.queryForList("select id from payer limit 1", Long.class).stream()
            .findFirst()
            .orElseGet(() ->
                jdbc.queryForObject(
                    "insert into payer (code, name, type) values ('RPP', 'Platca', 'SELF')"
                        + " returning id",
                    Long.class));
    return new Setup(reception, b1, b2, category, payer);
  }

  private long room(long building, long category, String number, int regular, int extra) {
    return jdbc.queryForObject(
        "insert into room (building_id, number, category_id, regular_beds, extra_beds)"
            + " values (?, ?, ?, ?, ?) returning id",
        Long.class,
        building,
        number,
        category,
        regular,
        extra);
  }

  private long bed(long room, int number, String type) {
    return jdbc.queryForObject(
        "insert into bed (room_id, bed_number, type) values (?, ?, ?) returning id",
        Long.class,
        room,
        number,
        type);
  }

  private long card(
      Setup s, String status, String arrival, String departure, int adults, int children) {
    long id =
        jdbc.queryForObject(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id, adults, children)"
                + " values (?, ?, ?::date, ?::date, ?, ?, ?, ?) returning id",
            Long.class,
            "RP" + System.nanoTime(),
            status,
            arrival,
            departure,
            s.category(),
            s.payer(),
            adults,
            children);
    created.add(id);
    return id;
  }

  private void stay(Setup s, long card, long bed, String from, String to) {
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, ?::date, ?::date, ?)",
        card,
        bed,
        from,
        to,
        s.category());
  }

  private void user(String username, String role) {
    long id = TestUsers.create(jdbc, encoder, username, "Heslo-na-test-1", false);
    jdbc.update(
        "insert into user_role (user_id, role_id) select ?, id from role where code = ?", id, role);
  }

  private String cardStatus(long card) {
    return jdbc.queryForObject("select status from reservation where id = ?", String.class, card);
  }

  @Test
  void personCountsForArrivalsDeparturesAndNoShow() {
    Setup s = setup();
    LocalDate day = LocalDate.parse(DAY);
    BusinessDayReport before = reports.build(s.reception(), day);

    long arrived = card(s, "CHECKED_IN", "2090-03-10", "2090-03-12", 1, 1); // 2 osoby
    stay(s, arrived, s.bedB1(), DAY, "2090-03-12");
    card(s, "CHECKED_IN", "2090-03-05", "2090-03-14", 2, 1); // v dome, 3 osoby
    card(s, "RESERVED", "2090-03-10", "2090-03-15", 2, 2); // neprišli 4 osoby
    card(s, "CHECKED_OUT", "2090-03-05", DAY, 1, 0); // odišiel 1 osoba
    card(s, "CHECKED_IN", "2090-03-05", DAY, 2, 0); // mal odísť, ešte je v dome, 2 osoby
    card(s, "CANCELLED", "2090-03-10", "2090-03-11", 5, 0); // storno sa nepočíta

    BusinessDayReport after = reports.build(s.reception(), day);
    assertThat(after.arrivals().expectedPersons() - before.arrivals().expectedPersons())
        .isEqualTo(2 + 4);
    assertThat(after.arrivals().arrivedPersons() - before.arrivals().arrivedPersons()).isEqualTo(2);
    assertThat(after.arrivals().noShowPersons() - before.arrivals().noShowPersons()).isEqualTo(4);
    assertThat(after.departures().expectedPersons() - before.departures().expectedPersons())
        .isEqualTo(1 + 2);
    assertThat(after.departures().departedPersons() - before.departures().departedPersons())
        .isEqualTo(1);
    assertThat(after.inHouse().endOfDay() - before.inHouse().endOfDay()).isEqualTo(2 + 3 + 2);
    // na začiatku dňa: bez 2 osôb, ktoré dnes prišli, a s 1 osobou, ktorá dnes odišla
    assertThat(after.inHouse().startOfDay() - before.inHouse().startOfDay())
        .isEqualTo((2 + 3 + 2) - 2 + 1);
  }

  @Test
  void occupancyCountsBlockedBedsAndExtraBed() {
    Setup s = setup();
    long a = card(s, "CHECKED_IN", "2090-03-08", "2090-03-13", 1, 0);
    stay(s, a, s.bedB1(), "2090-03-08", "2090-03-13");
    long b = card(s, "CHECKED_IN", "2090-03-10", "2090-03-12", 1, 0);
    stay(s, b, s.bedB2(), DAY, "2090-03-12"); // prístelka
    long cancelled = card(s, "CANCELLED", "2090-03-10", "2090-03-12", 1, 0);
    stay(s, cancelled, s.bedB1(), "2090-03-13", "2090-03-14"); // mimo dňa, nerátať

    BusinessDayReport report = reports.build(s.reception(), LocalDate.parse(DAY));
    // 5 bežných lôžok (A2 + B1 + C2), 2 blokované, 2 obsadené (z toho 1 prístelka)
    assertThat(report.occupancy().buildings()).hasSize(1);
    var stats = report.occupancy().complex();
    assertThat(stats.beds()).isEqualTo(5);
    assertThat(stats.blocked()).isEqualTo(2);
    assertThat(stats.occupied()).isEqualTo(2);
    assertThat(stats.free()).isEqualTo(1);
    assertThat(stats.percent()).isEqualTo(66.7);
    assertThat(report.occupancy().buildings().get(0).stats()).isEqualTo(stats);
  }

  @Test
  void forecastCoversFourteenDaysFromNextDay() {
    Setup s = setup();
    long reserved = card(s, "RESERVED", "2090-03-11", "2090-03-14", 1, 0);
    stay(s, reserved, s.bedB1(), "2090-03-11", "2090-03-14");
    long noShow = card(s, "NO_SHOW", "2090-03-11", "2090-03-14", 1, 0);
    stay(s, noShow, s.bedB2(), "2090-03-11", "2090-03-14"); // neprišiel sa nerátá

    var forecast = reports.build(s.reception(), LocalDate.parse(DAY)).forecast();
    assertThat(forecast).hasSize(14);
    assertThat(forecast.get(0).date()).isEqualTo(LocalDate.parse("2090-03-11"));
    assertThat(forecast.get(13).date()).isEqualTo(LocalDate.parse("2090-03-24"));
    // 11. a 12. marca je blokovaná izba A len 11. (blokácia do 12. vrátane polootvoreného konca)
    // 11.: 5 − 2 = 3 použiteľné, 1 obsadené → 33,3 %; 12. a 13.: 5 použiteľných → 20 %
    assertThat(forecast.get(0).percent()).isEqualTo(33.3);
    assertThat(forecast.get(1).percent()).isEqualTo(20.0);
    assertThat(forecast.get(2).percent()).isEqualTo(20.0);
    assertThat(forecast.get(3).percent()).isZero();
  }

  @Test
  void previewContainsReportAndNoShowDoesNotBlock() throws Exception {
    Setup s = setup();
    card(s, "RESERVED", "2090-03-10", "2090-03-15", 2, 0);
    user("rp_recep1", "RECEPTIONIST");
    mvc.perform(
            post("/api/receptions/" + s.reception() + "/business-day/close/preview")
                .with(csrf())
                .with(TestUsers.as(jdbc, "rp_recep1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canClose").value(true))
        .andExpect(jsonPath("$.info[?(@.ruleCode == 'NO_SHOW')]").isNotEmpty())
        .andExpect(jsonPath("$.report.arrivals.noShowPersons").isNumber())
        .andExpect(jsonPath("$.report.departures.expectedPersons").isNumber())
        .andExpect(jsonPath("$.report.inHouse.endOfDay").isNumber())
        .andExpect(jsonPath("$.report.occupancy.complex.beds").value(5))
        .andExpect(jsonPath("$.report.occupancy.buildings.length()").value(1))
        .andExpect(jsonPath("$.report.forecast.length()").value(14));
  }

  @Test
  void noShowRuleStaysInfoEvenWhenConfiguredAsBlocking() throws Exception {
    Setup s = setup();
    card(s, "RESERVED", "2090-03-10", "2090-03-15", 1, 0);
    jdbc.update(
        "insert into closing_rule (code, enabled, severity, reception_id)"
            + " values ('NO_SHOW', true, 'BLOCKING', ?)",
        s.reception());
    user("rp_recep2", "RECEPTIONIST");
    mvc.perform(
            post("/api/receptions/" + s.reception() + "/business-day/close/preview")
                .with(csrf())
                .with(TestUsers.as(jdbc, "rp_recep2")))
        .andExpect(jsonPath("$.canClose").value(true))
        .andExpect(jsonPath("$.blocking[?(@.ruleCode == 'NO_SHOW')]").isEmpty());
  }

  @Test
  void closeSetsNoShowStoresReportAndArchiveIsReadable() throws Exception {
    Setup s = setup();
    long missed = card(s, "RESERVED", "2090-03-10", "2090-03-15", 2, 0);
    long future = card(s, "RESERVED", "2090-03-11", "2090-03-15", 1, 0);
    long inHouse = card(s, "CHECKED_IN", "2090-03-08", "2090-03-14", 1, 0);
    stay(s, inHouse, s.bedB1(), "2090-03-08", "2090-03-14");
    user("rp_mgr", "RECEPTION_MANAGER");
    var who = TestUsers.as(jdbc, "rp_mgr");
    String base = "/api/receptions/" + s.reception() + "/business-day";

    mvc.perform(post(base + "/close").with(csrf()).with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessDate").value("2090-03-11"));

    assertThat(cardStatus(missed)).isEqualTo("NO_SHOW");
    assertThat(cardStatus(future)).isEqualTo("RESERVED");
    assertThat(cardStatus(inHouse)).isEqualTo("CHECKED_IN");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'BUSINESS_DAY_CLOSE'"
                    + " and outcome = 'SUCCESS'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);

    mvc.perform(get(base + "/reports?from=2090-03-01&to=2090-03-31").with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].businessDate").value(DAY));
    mvc.perform(get(base + "/reports?from=2090-04-01").with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get(base + "/reports/" + DAY).with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.report.occupancy.complex.beds").value(5))
        .andExpect(jsonPath("$.report.occupancy.complex.occupied").value(1))
        .andExpect(jsonPath("$.report.forecast.length()").value(14));
    mvc.perform(get(base + "/reports/2090-03-09").with(who)).andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "select report::text from business_day_report where reception_id = ?",
                String.class,
                s.reception()))
        .doesNotContain("Test rp_mgr")
        .doesNotContain("firstName");
  }

  @Test
  void archiveRequiresCloseOrReportViewPermission() throws Exception {
    Setup s = setup();
    String url = "/api/receptions/" + s.reception() + "/business-day/reports";
    user("rp_doctor", "DOCTOR");
    mvc.perform(get(url).with(TestUsers.as(jdbc, "rp_doctor"))).andExpect(status().isForbidden());

    // vlastná rola len s REPORT_VIEW (bez BUSINESS_DAY_CLOSE)
    jdbc.update("insert into role (code, name) values ('RP_VIEWER', 'Len report')");
    jdbc.update(
        "insert into role_permission (role_id, permission_code)"
            + " select id, 'REPORT_VIEW' from role where code = 'RP_VIEWER'");
    user("rp_viewer", "RP_VIEWER");
    mvc.perform(get(url).with(TestUsers.as(jdbc, "rp_viewer"))).andExpect(status().isOk());
    mvc.perform(
            post(url.replace("/reports", "/close/preview"))
                .with(csrf())
                .with(TestUsers.as(jdbc, "rp_viewer")))
        .andExpect(status().isForbidden());
  }
}
