package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.businessday.application.BusinessDayService;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class StayIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager tx;
  @Autowired BusinessDayService businessDays;

  /** Izba A: 2 riadne lôžka + prístelka (do 10 rokov), izba B: 2 riadne lôžka, iná kategória. */
  private record Fx(
      long reception,
      long category,
      long otherCategory,
      long roomA,
      long bedA1,
      long bedA2,
      long extraA,
      long roomB,
      long bedB1) {}

  private Fx fx() {
    int n = ++seq;
    long centre =
        insert("insert into centre (code, name) values ('ST" + n + "', 'Test') returning id");
    long reception =
        insert(
            "insert into reception (code, name) values ('STR"
                + n
                + "', 'Test recepcia') returning id");
    long building =
        jdbc.queryForObject(
            "insert into building (centre_id, reception_id, code, name) values (?, ?, 'B', 'Budova') returning id",
            Long.class,
            centre,
            reception);
    long base = insert("insert into base_category (name) values ('STK" + n + "') returning id");
    long category = category(centre, base, "K1");
    long otherCategory = category(centre, base, "K2");
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, '2030-01-10', 'OPEN')",
        reception);
    long roomA = room(building, "A", category, 2, 1, 10);
    long roomB = room(building, "B", otherCategory, 2, 0, null);
    return new Fx(
        reception,
        category,
        otherCategory,
        roomA,
        bed(roomA, 1),
        bed(roomA, 2),
        bed(roomA, 3),
        roomB,
        bed(roomB, 1));
  }

  private long insert(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  private long category(long centre, long base, String code) {
    return jdbc.queryForObject(
        "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
            + " values (?, ?, ?, ?, 2) returning id",
        Long.class,
        centre,
        base,
        code,
        "Kat " + code);
  }

  /** Izba a jej lôžka v jednej transakcii (kontrola počtu prístelok je odložená do commitu). */
  private long room(
      long building, String number, long category, int regular, int extra, Integer maxAge) {
    return new TransactionTemplate(tx)
        .execute(s -> {
          long id =
              jdbc.queryForObject(
                  "insert into room (building_id, number, category_id, regular_beds, extra_beds,"
                      + " extra_bed_max_age) values (?, ?, ?, ?, ?, ?) returning id",
                  Long.class,
                  building,
                  number,
                  category,
                  regular,
                  extra,
                  maxAge);
          for (int i = 1; i <= regular + extra; i++) {
            jdbc.update(
                "insert into bed (room_id, bed_number, type) values (?, ?, ?)",
                id,
                i,
                i <= regular ? "REGULAR" : "EXTRA");
          }
          return id;
        });
  }

  private long bed(long room, int number) {
    return jdbc.queryForObject(
        "select id from bed where room_id = ? and bed_number = ?", Long.class, room, number);
  }

  private long payer() {
    return jdbc.queryForList("select id from payer limit 1", Long.class).stream()
        .findFirst()
        .orElseGet(() ->
            insert(
                "insert into payer (code, name, type) values ('STP', 'Platca', 'SELF') returning id"));
  }

  private long card(Fx fx, String arrival, String departure, boolean exclusive, String birthDate) {
    long id =
        jdbc.queryForObject(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id, exclusive_room_use)"
                + " values (?, 'RESERVED', ?::date, ?::date, ?, ?, ?) returning id",
            Long.class,
            "ST" + System.nanoTime(),
            arrival,
            departure,
            fx.category(),
            payer(),
            exclusive);
    long client =
        jdbc.queryForObject(
            "insert into client (first_name, last_name, birth_date) values ('Test', 'Hosť', ?::date) returning id",
            Long.class,
            birthDate);
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest) values (?, ?, true)",
        id,
        client);
    return id;
  }

  private long card(Fx fx, String arrival, String departure) {
    return card(fx, arrival, departure, false, "1970-01-01");
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
    return user("stay.recepcna", "RECEPTIONIST");
  }

  private RequestPostProcessor manager() {
    return user("stay.veduca", "RECEPTION_MANAGER");
  }

  private static String body(long bed, String from, String to, boolean adjust, String... confirm) {
    return "{\"bedId\":%d,\"from\":\"%s\",\"to\":\"%s\",\"adjustReservationDates\":%s,\"confirm\":[%s]}"
        .formatted(
            bed,
            from,
            to,
            adjust,
            String.join(",", java.util.Arrays.stream(confirm).map(c -> "\"" + c + "\"").toList()));
  }

  private MvcResult stay(RequestPostProcessor who, long card, String body) throws Exception {
    return mvc.perform(
            post("/api/reservations/" + card + "/stays")
                .with(csrf())
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private static int status(MvcResult r) {
    return r.getResponse().getStatus();
  }

  private static String json(MvcResult r) throws Exception {
    return r.getResponse().getContentAsString();
  }

  private static String code(MvcResult r) throws Exception {
    return JsonPath.read(json(r), "$.code");
  }

  @Test
  void accommodatesOnFreeBedWithPriceCategoryOfRoomAndAudit() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-17");

    MvcResult result =
        stay(receptionist(), card, body(fx.bedA1(), "2030-01-10", "2030-01-17", false));

    assertThat(status(result)).isEqualTo(201);
    String json = json(result);
    assertThat(JsonPath.<Integer>read(json, "$.bedNumber")).isEqualTo(1);
    assertThat(JsonPath.<String>read(json, "$.bedType")).isEqualTo("REGULAR");
    assertThat(JsonPath.<Integer>read(json, "$.priceCategoryId").longValue())
        .isEqualTo(fx.category());
    assertThat(JsonPath.<Boolean>read(json, "$.locked")).isFalse();
    assertThat(JsonPath.<String>read(json, "$.roomNumber")).isEqualTo("A");

    String eventId = result.getResponse().getHeader("X-Audit-Event-Id");
    assertThat(eventId).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "select operation || '/' || use_case || '/' || outcome from audit_event where id = ?",
                String.class,
                Long.valueOf(eventId)))
        .isEqualTo("STAY_CREATE/UC1/SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_change where audit_event_id = ? and entity_type = 'stay'"
                    + " and change_type = 'CREATE'",
                Integer.class,
                Long.valueOf(eventId)))
        .isEqualTo(1);
  }

  @Test
  void listShowsStaysAndAuditsReadWithNameScope() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-12");
    assertThat(
            status(stay(receptionist(), card, body(fx.bedA1(), "2030-01-10", "2030-01-12", false))))
        .isEqualTo(201);

    MvcResult result =
        mvc.perform(get("/api/reservations/" + card + "/stays").with(receptionist())).andReturn();

    assertThat(status(result)).isEqualTo(200);
    assertThat(JsonPath.<List<?>>read(json(result), "$")).hasSize(1);
    assertThat(JsonPath.<String>read(json(result), "$[0].buildingName")).isEqualTo("Budova");
    assertThat(JsonPath.<String>read(json(result), "$[0].dateTo")).isEqualTo("2030-01-12");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event e join audit_subject_access a on a.audit_event_id = e.id"
                    + " where e.event_type = 'READ' and e.operation = 'STAY_VIEW' and a.data_scope = 'NAME'"
                    + " and a.client_id = (select client_id from reservation_guest where reservation_id = ?)",
                Integer.class,
                card))
        .isEqualTo(1);
    assertThat(
            status(
                mvc.perform(get("/api/reservations/999999999/stays").with(receptionist()))
                    .andReturn()))
        .isEqualTo(404);
  }

  @Test
  void accommodatesOnExtraBedAndChecksGuestAge() throws Exception {
    Fx fx = fx();
    long child = card(fx, "2030-01-10", "2030-01-12", false, "2025-01-01");
    long adult = card(fx, "2030-01-10", "2030-01-12", false, "1970-01-01");

    assertThat(
            status(
                stay(receptionist(), child, body(fx.extraA(), "2030-01-10", "2030-01-12", false))))
        .isEqualTo(201);
    MvcResult tooOld =
        stay(receptionist(), adult, body(fx.extraA(), "2030-01-10", "2030-01-12", false));
    assertThat(status(tooOld)).isEqualTo(422);
    assertThat(code(tooOld)).isEqualTo("EXTRA_BED_AGE");
  }

  @Test
  void collisionOnSameBedIsRejectedButDepartureDayMayBeNextArrival() throws Exception {
    Fx fx = fx();
    long first = card(fx, "2030-01-10", "2030-01-15");
    long second = card(fx, "2030-01-12", "2030-01-18");
    long third = card(fx, "2030-01-15", "2030-01-18");
    assertThat(
            status(
                stay(receptionist(), first, body(fx.bedA1(), "2030-01-10", "2030-01-15", false))))
        .isEqualTo(201);

    MvcResult clash =
        stay(receptionist(), second, body(fx.bedA1(), "2030-01-12", "2030-01-18", false));
    assertThat(status(clash)).isEqualTo(409);
    assertThat(code(clash)).isEqualTo("BED_OCCUPIED");

    assertThat(
            status(
                stay(receptionist(), third, body(fx.bedA1(), "2030-01-15", "2030-01-18", false))))
        .isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'STAY_CREATE'"
                    + " and outcome = 'REJECTED' and error_code = 'BED_OCCUPIED'",
                Integer.class))
        .isGreaterThanOrEqualTo(1);
  }

  @Test
  void backdatedAccommodationNeedsBackdatePermission() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-08", "2030-01-12");

    MvcResult denied =
        stay(receptionist(), card, body(fx.bedA1(), "2030-01-08", "2030-01-12", false));
    assertThat(status(denied)).isEqualTo(422);
    assertThat(code(denied)).isEqualTo("PAST_CHANGE");

    assertThat(status(stay(manager(), card, body(fx.bedA1(), "2030-01-08", "2030-01-12", false))))
        .isEqualTo(201);
  }

  @Test
  void intervalOutsideCardNeedsAdjustFlagAndThenAdjustsCard() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-12");

    MvcResult outside =
        stay(receptionist(), card, body(fx.bedA1(), "2030-01-10", "2030-01-15", false));
    assertThat(status(outside)).isEqualTo(422);
    assertThat(code(outside)).isEqualTo("OUTSIDE_RESERVATION");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from stay where reservation_id = ?", Integer.class, card))
        .isZero();

    assertThat(
            status(stay(receptionist(), card, body(fx.bedA1(), "2030-01-10", "2030-01-15", true))))
        .isEqualTo(201);
    assertThat(
            jdbc.queryForObject(
                "select arrival_date || '/' || departure_date from reservation where id = ?",
                String.class,
                card))
        .isEqualTo("2030-01-10/2030-01-15");
  }

  @Test
  void exclusiveCardCannotEnterOccupiedRoom() throws Exception {
    Fx fx = fx();
    long other = card(fx, "2030-01-10", "2030-01-14");
    long exclusive = card(fx, "2030-01-10", "2030-01-14", true, "1970-01-01");
    assertThat(
            status(
                stay(receptionist(), other, body(fx.bedA1(), "2030-01-10", "2030-01-14", false))))
        .isEqualTo(201);

    MvcResult result =
        stay(receptionist(), exclusive, body(fx.bedA2(), "2030-01-10", "2030-01-14", false));

    assertThat(status(result)).isEqualTo(409);
    assertThat(code(result)).isEqualTo("ROOM_NOT_EMPTY");
  }

  @Test
  void heldBedWarningNeedsConfirmation() throws Exception {
    Fx fx = fx();
    long exclusive = card(fx, "2030-01-10", "2030-01-14", true, "1970-01-01");
    long other = card(fx, "2030-01-10", "2030-01-14");
    assertThat(
            status(
                stay(
                    receptionist(),
                    exclusive,
                    body(fx.bedA1(), "2030-01-10", "2030-01-14", false))))
        .isEqualTo(201);

    MvcResult warning =
        stay(receptionist(), other, body(fx.bedA2(), "2030-01-10", "2030-01-14", false));
    assertThat(status(warning)).isEqualTo(409);
    assertThat(JsonPath.<Boolean>read(json(warning), "$.confirmable")).isTrue();
    assertThat(JsonPath.<String>read(json(warning), "$.warnings[0].code"))
        .isEqualTo("RELEASES_HELD_BED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from stay where reservation_id = ?", Integer.class, other))
        .isZero();

    MvcResult confirmed =
        stay(
            receptionist(),
            other,
            body(fx.bedA2(), "2030-01-10", "2030-01-14", false, "RELEASES_HELD_BED"));
    assertThat(status(confirmed)).isEqualTo(201);
    String payload =
        jdbc.queryForObject(
            "select request_payload::text from audit_event where id = ?",
            String.class,
            Long.valueOf(confirmed.getResponse().getHeader("X-Audit-Event-Id")));
    assertThat(payload).contains("RELEASES_HELD_BED");
  }

  @Test
  void stayLongerThanNightLimitIsRejected() throws Exception {
    Fx fx = fx();
    long role =
        insert(
            "insert into role (code, name) values ('STAY_LIMIT" + seq + "', 'Limit') returning id");
    jdbc.update(
        "insert into role_permission (role_id, permission_code, limit_value) values (?, 'STAY_CREATE', 3)",
        role);
    jdbc.update(
        "insert into role_permission (role_id, permission_code) values (?, 'RESERVATION_VIEW')",
        role);
    long userId = TestUsers.create(jdbc, encoder, "stay.limit" + seq, PASSWORD, false);
    jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", userId, role);
    RequestPostProcessor limited = TestUsers.as(jdbc, "stay.limit" + seq);
    long card = card(fx, "2030-01-10", "2030-01-20");

    MvcResult tooLong = stay(limited, card, body(fx.bedA1(), "2030-01-10", "2030-01-14", false));
    assertThat(status(tooLong)).isEqualTo(422);
    assertThat(code(tooLong)).isEqualTo("MAX_NIGHTS_EXCEEDED");

    assertThat(status(stay(limited, card, body(fx.bedA1(), "2030-01-10", "2030-01-13", false))))
        .isEqualTo(201);
  }

  @Test
  void concurrentRequestsForSameBedYieldOneCreatedAndOneConflict() throws Exception {
    Fx fx = fx();
    long first = card(fx, "2030-01-10", "2030-01-14");
    long second = card(fx, "2030-01-10", "2030-01-14");
    RequestPostProcessor who = receptionist();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (long card : List.of(first, second)) {
        futures.add(
            pool.submit(() -> {
              start.await();
              return status(stay(who, card, body(fx.bedA1(), "2030-01-10", "2030-01-14", false)));
            }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> f : futures) {
        statuses.add(f.get());
      }
      Collections.sort(statuses);
      assertThat(statuses).containsExactly(201, 409);
    } finally {
      pool.shutdownNow();
    }
    assertThat(
            jdbc.queryForObject(
                "select count(*) from stay where bed_id = ?", Integer.class, fx.bedA1()))
        .isEqualTo(1);
  }

  @Test
  void deletesFutureStayAndRefusesPastOne() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-05", "2030-01-15");
    MvcResult created = stay(manager(), card, body(fx.bedA1(), "2030-01-12", "2030-01-15", false));
    long futureId = ((Number) JsonPath.read(json(created), "$.id")).longValue();
    long pastId =
        insert(
            "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
                + " values ("
                + card
                + ", "
                + fx.bedA2()
                + ", '2030-01-05', '2030-01-12', "
                + fx.category()
                + ") returning id");

    MvcResult past =
        mvc.perform(delete("/api/stays/" + pastId).with(csrf()).with(receptionist())).andReturn();
    assertThat(status(past)).isEqualTo(422);
    assertThat(code(past)).isEqualTo("PAST_CHANGE");

    MvcResult ok =
        mvc.perform(delete("/api/stays/" + futureId).with(csrf()).with(receptionist())).andReturn();
    assertThat(status(ok)).isEqualTo(204);
    assertThat(
            jdbc.queryForObject("select count(*) from stay where id = ?", Integer.class, futureId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event e join audit_change c on c.audit_event_id = e.id"
                    + " where e.id = ? and e.operation = 'STAY_DELETE' and c.change_type = 'DELETE'",
                Integer.class,
                Long.valueOf(ok.getResponse().getHeader("X-Audit-Event-Id"))))
        .isEqualTo(1);
    assertThat(
            status(
                mvc.perform(delete("/api/stays/999999999").with(csrf()).with(receptionist()))
                    .andReturn()))
        .isEqualTo(404);
  }

  @Test
  void permissionsAndReceptionScopeAreEnforced() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-12");
    String body = body(fx.bedA1(), "2030-01-10", "2030-01-12", false);

    assertThat(status(stay(user("stay.lekar", "DOCTOR"), card, body))).isEqualTo(403);

    long otherReception =
        insert("insert into reception (code, name) values ('STX" + seq + "', 'Iná') returning id");
    long userId = TestUsers.create(jdbc, encoder, "stay.ina" + seq, PASSWORD, false);
    jdbc.update(
        "insert into user_role (user_id, role_id, reception_id) select ?, id, ? from role where code = 'RECEPTIONIST'",
        userId,
        otherReception);
    assertThat(status(stay(TestUsers.as(jdbc, "stay.ina" + seq), card, body))).isEqualTo(403);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from stay where reservation_id = ?", Integer.class, card))
        .isZero();
  }

  @Test
  void availableRoomsListFreeBedsAndFilterByCategoryAndExtraBeds() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-14");
    assertThat(
            status(stay(receptionist(), card, body(fx.bedA1(), "2030-01-10", "2030-01-14", false))))
        .isEqualTo(201);
    String base =
        "/api/rooms/available?from=2030-01-12&to=2030-01-16&receptionId=" + fx.reception();

    String sameCategory =
        json(
            mvc.perform(get(base + "&categoryId=" + fx.category()).with(receptionist()))
                .andReturn());
    assertThat(JsonPath.<List<?>>read(sameCategory, "$")).hasSize(1);
    assertThat(JsonPath.<List<?>>read(sameCategory, "$[0].freeBeds")).hasSize(1);
    assertThat(JsonPath.<Integer>read(sameCategory, "$[0].freeBeds[0].bedNumber")).isEqualTo(2);

    String all =
        json(
            mvc.perform(
                    get(base
                            + "&categoryId="
                            + fx.category()
                            + "&allCategories=true&extraBeds=true")
                        .with(receptionist()))
                .andReturn());
    assertThat(JsonPath.<List<?>>read(all, "$")).hasSize(2);
    assertThat(JsonPath.<List<Boolean>>read(all, "$[*].matchesCategory"))
        .containsExactly(true, false);
    assertThat(JsonPath.<List<?>>read(all, "$[0].freeBeds")).hasSize(2);

    String laterInterval =
        json(
            mvc.perform(
                    get("/api/rooms/available?from=2030-01-14&to=2030-01-16&receptionId="
                            + fx.reception()
                            + "&categoryId="
                            + fx.category())
                        .with(receptionist()))
                .andReturn());
    assertThat(JsonPath.<List<?>>read(laterInterval, "$[0].freeBeds")).hasSize(2);

    jdbc.update("insert into block_reason (name) values ('Test dôvod " + seq + "')");
    jdbc.update(
        "insert into room_block (room_id, reason_id, date_from, date_to)"
            + " select ?, max(id), '2030-01-15', '2030-01-20' from block_reason",
        fx.roomB());
    String blocked =
        json(mvc.perform(get(base + "&allCategories=true").with(receptionist())).andReturn());
    assertThat(JsonPath.<List<Integer>>read(blocked, "$[*].roomId"))
        .containsExactly((int) fx.roomA());

    assertThat(
            status(
                mvc.perform(
                        get("/api/rooms/available?from=2030-01-16&to=2030-01-12")
                            .with(receptionist()))
                    .andReturn()))
        .isEqualTo(400);
  }

  @Test
  void heldBedsAreNotOfferedAsAvailable() throws Exception {
    Fx fx = fx();
    long exclusive = card(fx, "2030-01-10", "2030-01-14", true, "1970-01-01");
    assertThat(
            status(
                stay(
                    receptionist(),
                    exclusive,
                    body(fx.bedA1(), "2030-01-10", "2030-01-14", false))))
        .isEqualTo(201);

    String rooms =
        json(
            mvc.perform(
                    get("/api/rooms/available?from=2030-01-11&to=2030-01-12&allCategories=true&receptionId="
                            + fx.reception())
                        .with(receptionist()))
                .andReturn());

    assertThat(JsonPath.<List<Integer>>read(rooms, "$[*].roomId"))
        .containsExactly((int) fx.roomB());
  }

  @Test
  void databaseRejectsOverlappingStaysAndInvalidInterval() {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-20");
    String insert =
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, ?::date, ?::date, ?)";
    jdbc.update(insert, card, fx.bedA1(), "2030-01-10", "2030-01-15", fx.category());

    assertThatThrownBy(
            () -> jdbc.update(insert, card, fx.bedA1(), "2030-01-14", "2030-01-18", fx.category()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () -> jdbc.update(insert, card, fx.bedA2(), "2030-01-14", "2030-01-14", fx.category()))
        .isInstanceOf(DataAccessException.class);
    jdbc.update(insert, card, fx.bedA1(), "2030-01-15", "2030-01-18", fx.category());
  }

  @Test
  void closingRuleBlocksCheckedInCardWithoutStay() {
    Fx fx = fx();
    long without = card(fx, "2030-01-08", "2030-01-12");
    long with = card(fx, "2030-01-08", "2030-01-12");
    jdbc.update("update reservation set status = 'CHECKED_IN' where id in (?, ?)", without, with);
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " values (?, ?, '2030-01-08', '2030-01-12', ?)",
        with,
        fx.bedA1(),
        fx.category());

    try {
      var preview = businessDays.preview(fx.reception());

      assertThat(preview.canClose()).isFalse();
      assertThat(preview.blocking())
          .anyMatch(
              f -> f.ruleCode().equals("CHECKED_IN_WITHOUT_STAY") && f.reservationId() == without)
          .noneMatch(f -> f.reservationId() != null && f.reservationId() == with);
    } finally {
      // Karta bez pobytu sa hlási každej recepcii, preto ju neponechať ďalším testom.
      jdbc.update(
          "update reservation set status = 'CHECKED_OUT' where id in (?, ?)", without, with);
    }
  }
}
