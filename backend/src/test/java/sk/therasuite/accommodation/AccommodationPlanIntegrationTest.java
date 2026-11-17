package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, SqlCounter.class})
class AccommodationPlanIntegrationTest {

  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager tx;
  @Autowired AccommodationQueries queries;

  /** Dve recepcie s jednou budovou; izby 1 a 2 patria recepcii A, izba 3 recepcii B. */
  private record Fx(
      long receptionA,
      long receptionB,
      long buildingA,
      long buildingB,
      long room1,
      long room2,
      long room3,
      long category,
      long payer) {}

  private Fx fx() {
    int n = ++seq;
    long centre = id("insert into centre (code, name) values ('PL" + n + "', 'Plán') returning id");
    long recA =
        id("insert into reception (code, name) values ('PA" + n + "', 'Recepcia A') returning id");
    long recB =
        id("insert into reception (code, name) values ('PB" + n + "', 'Recepcia B') returning id");
    long buA = building(centre, recA, "A");
    long buB = building(centre, recB, "B");
    long base = id("insert into base_category (name) values ('PLK" + n + "') returning id");
    long category =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " select "
                + centre
                + ", "
                + base
                + ", 'K2', 'Dvojlôžková', 2 returning id");
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, '2030-01-12', 'OPEN')",
        recA);
    long payer =
        id(
            "insert into payer (code, name, type) values ('PLP"
                + n
                + "', 'Platca', 'SELF') returning id");
    return new Fx(
        recA,
        recB,
        buA,
        buB,
        room(buA, "1", category),
        room(buA, "2", category),
        room(buB, "3", category),
        category,
        payer);
  }

  private long id(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private long building(long centre, long reception, String code) {
    return jdbc.queryForObject(
        "insert into building (centre_id, reception_id, code, name) values (?, ?, ?, 'Budova') returning id",
        Long.class,
        centre,
        reception,
        code);
  }

  /** Izba s dvoma riadnymi lôžkami (lôžka 1 a 2). */
  private long room(long building, String number, long category) {
    return new TransactionTemplate(tx)
        .execute(s -> {
          long id =
              jdbc.queryForObject(
                  "insert into room (building_id, number, category_id, regular_beds)"
                      + " values (?, ?, ?, 2) returning id",
                  Long.class,
                  building,
                  number,
                  category);
          jdbc.update("insert into bed (room_id, bed_number, type) values (?, 1, 'REGULAR')", id);
          jdbc.update("insert into bed (room_id, bed_number, type) values (?, 2, 'REGULAR')", id);
          return id;
        });
  }

  private long bed(long room, int number) {
    return jdbc.queryForObject(
        "select id from bed where room_id = ? and bed_number = ?", Long.class, room, number);
  }

  /** Karta s jedným hosťom a pobytom na lôžku; vracia ID klienta. */
  private long stay(
      Fx fx, long room, int bedNumber, String from, String to, boolean exclusive, String last) {
    long card =
        jdbc.queryForObject(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id, exclusive_room_use, early_check_in)"
                + " values (?, 'CHECKED_IN', ?::date, ?::date, ?, ?, ?, true) returning id",
            Long.class,
            "PL" + System.nanoTime(),
            from,
            to,
            fx.category(),
            fx.payer(),
            exclusive);
    long client =
        jdbc.queryForObject(
            "insert into client (first_name, last_name) values ('Anna', ?) returning id",
            Long.class,
            last);
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest) values (?, ?, true)",
        card,
        client);
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, ?::date, ?::date, ?)",
        card,
        bed(room, bedNumber),
        from,
        to,
        fx.category());
    return client;
  }

  private RequestPostProcessor globalUser(String username) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, "Heslo-na-test-1", false, "RECEPTIONIST");
    }
    return TestUsers.as(jdbc, username);
  }

  /** Používateľ s rolou recepčnej len pre jednu recepciu. */
  private RequestPostProcessor scopedUser(String username, long receptionId) {
    TestUsers.create(jdbc, encoder, username, "Heslo-na-test-1", false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id)"
            + " select u.id, r.id, ? from user_account u, role r where u.username = ? and r.code = 'RECEPTIONIST'",
        receptionId,
        username);
    return TestUsers.as(jdbc, username);
  }

  private MvcResult plan(RequestPostProcessor who, String query) throws Exception {
    return mvc.perform(
            get("/api/accommodation-plan?from=2030-01-10&to=2030-01-20&" + query).with(who))
        .andReturn();
  }

  private static String json(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString();
  }

  @Test
  void returnsRoomsBedsBarsBusinessDayAndWritesAudit() throws Exception {
    Fx fx = fx();
    long c1 = stay(fx, fx.room1(), 1, "2030-01-05", "2030-01-14", false, "Testovský");
    long c2 = stay(fx, fx.room2(), 1, "2030-01-12", "2030-01-16", true, "Ukážkový");
    long reason =
        id("insert into block_reason (name) values ('Maľovanie " + seq + "') returning id");
    jdbc.update(
        "insert into room_block (room_id, reason_id, date_from, date_to, note)"
            + " values (?, ?, '2030-01-18', '2030-01-25', 'pozn')",
        fx.room1(),
        reason);

    MvcResult r = plan(globalUser("plan.recepcna"), "buildingIds=" + fx.buildingA());

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    String j = json(r);
    assertThat(JsonPath.<List<String>>read(j, "$.rooms[*].number")).containsExactly("1", "2");
    assertThat(JsonPath.<String>read(j, "$.rooms[0].building")).isEqualTo("A");
    assertThat(JsonPath.<String>read(j, "$.rooms[0].category.code")).isEqualTo("K2");
    assertThat(JsonPath.<List<String>>read(j, "$.rooms[0].beds[*].type"))
        .containsExactly("REGULAR", "REGULAR");
    // pobyt cez hranicu obdobia: orezaný, ale so skutočnými dátumami
    assertThat(JsonPath.<List<String>>read(j, "$.rooms[0].bars[?(@.type=='STAY')].from").get(0))
        .isEqualTo("2030-01-10");
    assertThat(JsonPath.<List<String>>read(j, "$.rooms[0].bars[?(@.type=='STAY')].stayFrom").get(0))
        .isEqualTo("2030-01-05");
    assertThat(JsonPath.<List<String>>read(j, "$.rooms[0].bars[?(@.type=='STAY')].label").get(0))
        .isEqualTo("Testovský A.");
    assertThat(
            JsonPath.<List<List<String>>>read(j, "$.rooms[0].bars[?(@.type=='STAY')].flags")
                .get(0)
                .get(0))
        .isEqualTo("EARLY_CHECK_IN");
    assertThat(JsonPath.<List<String>>read(j, "$.rooms[0].bars[?(@.type=='ROOM_BLOCK')].to").get(0))
        .isEqualTo("2030-01-20");
    assertThat(
            JsonPath.<List<String>>read(j, "$.rooms[0].bars[?(@.type=='ROOM_BLOCK')].blockTo")
                .get(0))
        .isEqualTo("2030-01-25");
    // držané lôžko len pri karte s výhradným užívaním
    assertThat(JsonPath.<List<Object>>read(j, "$.rooms[0].bars[?(@.type=='HELD_BED')]")).isEmpty();
    assertThat(JsonPath.<List<Object>>read(j, "$.rooms[1].bars[?(@.type=='HELD_BED')]")).hasSize(1);
    assertThat(JsonPath.<String>read(j, "$.receptions[0].businessDate")).isEqualTo("2030-01-12");

    String eventId = r.getResponse().getHeader("X-Audit-Event-Id");
    List<Long> audited =
        jdbc.queryForList(
            "select client_id from audit_subject_access a join audit_event e on e.id = a.audit_event_id"
                + " where e.operation = 'PLAN_VIEW' and a.data_scope = 'NAME'"
                + " and a.client_id in (?, ?) order by a.id desc limit 2",
            Long.class,
            c1,
            c2);
    assertThat(audited).containsExactlyInAnyOrder(c1, c2);
    assertThat(eventId == null || !eventId.isBlank()).isTrue();
  }

  @Test
  void scopesResultToUsersReception() throws Exception {
    Fx fx = fx();
    var scoped = scopedUser("plan.len.a." + seq, fx.receptionA());
    String both = "buildingIds=" + fx.buildingA() + "," + fx.buildingB();

    MvcResult r = plan(scoped, both);

    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(JsonPath.<List<String>>read(json(r), "$.rooms[*].number")).containsExactly("1", "2");
    assertThat(JsonPath.<List<Integer>>read(json(r), "$.receptions[*].receptionId")).hasSize(1);
    mvc.perform(
            get("/api/accommodation-plan?from=2030-01-10&to=2030-01-20&receptionId="
                    + fx.receptionB())
                .with(scoped))
        .andExpect(status().isForbidden());
  }

  @Test
  void roomIdsLoadsOnlySelectedRooms() throws Exception {
    Fx fx = fx();
    stay(fx, fx.room2(), 1, "2030-01-10", "2030-01-12", false, "Ukážkový");

    MvcResult r = plan(globalUser("plan.recepcna"), "roomIds=" + fx.room2());

    assertThat(JsonPath.<List<String>>read(json(r), "$.rooms[*].number")).containsExactly("2");
  }

  @Test
  void freeOnlyAndFullyFreeFilters() throws Exception {
    Fx fx = fx();
    // izba 1: oba lôžka obsadené, izba 2: jedno lôžko obsadené v období
    stay(fx, fx.room1(), 1, "2030-01-10", "2030-01-20", false, "Jeden");
    stay(fx, fx.room1(), 2, "2030-01-10", "2030-01-20", false, "Dva");
    stay(fx, fx.room2(), 1, "2030-01-10", "2030-01-15", false, "Tri");
    String b = "buildingIds=" + fx.buildingA() + "," + fx.buildingB();
    var user = globalUser("plan.recepcna");

    assertThat(
            JsonPath.<List<String>>read(
                json(plan(user, b + "&freeOnly=true")), "$.rooms[*].number"))
        .containsExactly("2", "3");
    assertThat(
            JsonPath.<List<String>>read(
                json(plan(user, b + "&fullyFree=true")), "$.rooms[*].number"))
        .containsExactly("3");
  }

  @Test
  void loadsPlanWithFourQueriesAndNoNPlusOne() throws Exception {
    Fx fx = fx();
    stay(fx, fx.room1(), 1, "2030-01-10", "2030-01-15", true, "Jeden");
    stay(fx, fx.room2(), 1, "2030-01-10", "2030-01-15", false, "Dva");

    int before = SqlCounter.STATEMENTS.size();
    var data =
        queries.loadPlan(
            new StayPeriod(LocalDate.parse("2030-01-10"), LocalDate.parse("2030-01-20")),
            List.of(fx.buildingA()),
            null,
            null,
            reception -> true);
    assertThat(SqlCounter.STATEMENTS.size() - before).isEqualTo(4);
    assertThat(data.rooms()).hasSize(2);

    // celé volanie API má rovnaký počet dotazov pre jednu izbu aj pre všetky izby
    var user = globalUser("plan.recepcna");
    plan(user, "roomIds=" + fx.room1()); // zahriatie cache oprávnení a pod.
    int one = countStatements(() -> plan(user, "roomIds=" + fx.room1()));
    int many = countStatements(() -> plan(user, "buildingIds=" + fx.buildingA()));
    assertThat(many).isEqualTo(one);
  }

  private int countStatements(ThrowingRunnable r) throws Exception {
    int before = SqlCounter.STATEMENTS.size();
    r.run();
    return SqlCounter.STATEMENTS.size() - before;
  }

  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
