package sk.therasuite.businessday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.businessday.application.BusinessDateProvider;
import sk.therasuite.businessday.application.BusinessDayService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class BusinessDayIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired BusinessDayService service;
  @Autowired BusinessDateProvider dates;

  private static int seq;

  private record Setup(long reception, long room) {}

  /** Nová fiktívna recepcia s budovou a izbou a otvoreným dňom 2030-01-10. */
  private Setup reception() {
    int n = ++seq;
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values (?, 'Test') returning id",
            Long.class,
            "BD" + n);
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values (?, 'Test recepcia') returning id",
            Long.class,
            "BDR" + n);
    long building =
        jdbc.queryForObject(
            "insert into building (centre_id, reception_id, code, name)"
                + " values (?, ?, 'B', 'Budova') returning id",
            Long.class,
            centre,
            reception);
    long base =
        jdbc.queryForObject(
            "insert into base_category (name) values (?) returning id", Long.class, "BDK" + n);
    long category =
        jdbc.queryForObject(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'K', 'Kat', 2) returning id",
            Long.class,
            centre,
            base);
    long room =
        jdbc.queryForObject(
            "insert into room (building_id, number, category_id, regular_beds) values (?, '1', ?, 2) returning id",
            Long.class,
            building,
            category);
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, '2030-01-10',"
            + " 'OPEN')",
        reception);
    return new Setup(reception, room);
  }

  private long card(String status, String arrival, String departure) {
    long category = jdbc.queryForObject("select min(id) from room_category", Long.class);
    Long payer =
        jdbc.queryForList("select id from payer limit 1", Long.class).stream()
            .findFirst()
            .orElseGet(() ->
                jdbc.queryForObject(
                    "insert into payer (code, name, type) values ('BDP', 'Platca', 'SELF')"
                        + " returning id",
                    Long.class));
    return jdbc.queryForObject(
        "insert into reservation (reservation_number, status, arrival_date, departure_date,"
            + " booked_category_id, payer_id) values (?, ?, ?::date, ?::date, ?, ?) returning id",
        Long.class,
        "BD" + System.nanoTime(),
        status,
        arrival,
        departure,
        category,
        payer);
  }

  private void user(String username, String role, Long receptionScope) {
    long id = TestUsers.create(jdbc, encoder, username, "Heslo-na-test-1", false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id) select ?, id, ? from role"
            + " where code = ?",
        id,
        receptionScope,
        role);
  }

  private String url(long reception, String suffix) {
    return "/api/receptions/" + reception + "/business-day" + suffix;
  }

  @Test
  void initialDayIsOpenedForReceptionWithoutDay() {
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values ('BDINIT', 'Bez dňa') returning id",
            Long.class);
    assertThat(service.openMissingDays()).isGreaterThanOrEqualTo(1);
    assertThat(dates.current(reception)).isNotNull();
    assertThat(service.openMissingDays()).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from business_day where reception_id = ?",
                Integer.class,
                reception))
        .isEqualTo(1);
  }

  @Test
  void onlyOneOpenDayPerReception() {
    Setup s = reception();
    org.junit.jupiter.api.Assertions.assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into business_day (reception_id, business_date, status)"
                    + " values (?, '2030-01-11', 'OPEN')",
                s.reception()));
  }

  @Test
  void providerResolvesByRoomAndLatestOf() {
    Setup a = reception();
    Setup b = reception();
    jdbc.update(
        "update business_day set business_date = '2030-01-12' where reception_id = ?",
        b.reception());
    assertThat(dates.currentForRoom(a.room())).isEqualTo(LocalDate.of(2030, 1, 10));
    assertThat(dates.latestOf(List.of(a.room(), b.room()))).isEqualTo(LocalDate.of(2030, 1, 12));
  }

  @Test
  void closingAdvancesOnlyOwnReceptionAndStoresSummary() throws Exception {
    Setup a = reception();
    Setup b = reception();
    user("bd_mgr1", "RECEPTIONIST", null);
    mvc.perform(post(url(a.reception(), "/close")).with(csrf()).with(TestUsers.as(jdbc, "bd_mgr1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessDate").value("2030-01-11"));
    assertThat(dates.current(a.reception())).isEqualTo(LocalDate.of(2030, 1, 11));
    assertThat(dates.current(b.reception())).isEqualTo(LocalDate.of(2030, 1, 10));
    assertThat(
            jdbc.queryForObject(
                "select closing_summary is not null and closed_by is not null from business_day"
                    + " where reception_id = ? and status = 'CLOSED'",
                Boolean.class,
                a.reception()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'BUSINESS_DAY_CLOSE'"
                    + " and outcome = 'SUCCESS'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void previewSplitsFindingsBySeverityAndDisabledRuleIsSkipped() throws Exception {
    Setup s = reception();
    card("CHECKED_IN", "2030-01-05", "2030-01-10");
    card("RESERVED", "2030-01-10", "2030-01-15");
    user("bd_mgr2", "RECEPTIONIST", null);
    var who = TestUsers.as(jdbc, "bd_mgr2");
    mvc.perform(post(url(s.reception(), "/close/preview")).with(csrf()).with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canClose").value(true))
        .andExpect(
            jsonPath("$.info.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

    // pre túto recepciu vypnúť NO_SHOW, odchody nechať ako informatívne
    jdbc.update(
        "insert into closing_rule (code, enabled, severity, reception_id) values"
            + " ('NO_SHOW', false, 'INFO', ?)",
        s.reception());
    mvc.perform(post(url(s.reception(), "/close/preview")).with(csrf()).with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info[?(@.ruleCode == 'NO_SHOW')]").isEmpty())
        .andExpect(jsonPath("$.info[?(@.ruleCode == 'DEPARTURES_NOT_CHECKED_OUT')]").isNotEmpty());
  }

  @Test
  void blockingRuleStopsClosing() throws Exception {
    Setup s = reception();
    card("CHECKED_IN", "2030-01-05", "2030-01-10");
    jdbc.update(
        "insert into closing_rule (code, enabled, severity, reception_id) values"
            + " ('DEPARTURES_NOT_CHECKED_OUT', true, 'BLOCKING', ?)",
        s.reception());
    user("bd_mgr3", "RECEPTIONIST", null);
    var who = TestUsers.as(jdbc, "bd_mgr3");
    mvc.perform(post(url(s.reception(), "/close/preview")).with(csrf()).with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canClose").value(false))
        .andExpect(jsonPath("$.blocking", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(1))));
    mvc.perform(post(url(s.reception(), "/close")).with(csrf()).with(who))
        .andExpect(status().isConflict());
    assertThat(dates.current(s.reception())).isEqualTo(LocalDate.of(2030, 1, 10));
  }

  @Test
  void permissionIsScopedToReception() throws Exception {
    Setup own = reception();
    Setup other = reception();
    user("bd_scoped", "RECEPTIONIST", own.reception());
    var who = TestUsers.as(jdbc, "bd_scoped");
    mvc.perform(get(url(own.reception(), "")).with(who))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.businessDate").value("2030-01-10"))
        .andExpect(jsonPath("$.status").value("OPEN"));
    mvc.perform(get(url(other.reception(), "")).with(who)).andExpect(status().isForbidden());
    mvc.perform(post(url(other.reception(), "/close/preview")).with(csrf()).with(who))
        .andExpect(status().isForbidden());
    mvc.perform(post(url(other.reception(), "/close")).with(csrf()).with(who))
        .andExpect(status().isForbidden());
    assertThat(dates.current(other.reception())).isEqualTo(LocalDate.of(2030, 1, 10));
  }

  @Test
  void roleWithoutPermissionGets403() throws Exception {
    Setup s = reception();
    user("bd_doctor", "DOCTOR", null);
    mvc.perform(get(url(s.reception(), "")).with(TestUsers.as(jdbc, "bd_doctor")))
        .andExpect(status().isForbidden());
  }
}
