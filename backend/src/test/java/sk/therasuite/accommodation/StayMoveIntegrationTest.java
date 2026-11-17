package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

/** Sťahovanie, zmena dĺžky, dávka a výmena pobytov (TK-12). Obchodný deň recepcie: 2030-01-10. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class StayMoveIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";
  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager tx;

  /** Izba A (kategória K1) a izba B (K2), každá s dvoma riadnymi lôžkami. */
  private record Fx(long category, long otherCategory, long a1, long a2, long b1, long b2) {}

  private Fx fx() {
    int n = ++seq;
    long centre =
        jdbc.queryForObject(
            "insert into centre (code, name) values ('MV" + n + "', 'Test') returning id",
            Long.class);
    long reception =
        jdbc.queryForObject(
            "insert into reception (code, name) values ('MVR" + n + "', 'Test') returning id",
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
            "insert into base_category (name) values ('MVK" + n + "') returning id", Long.class);
    long k1 = category(centre, base, "K1");
    long k2 = category(centre, base, "K2");
    jdbc.update(
        "insert into business_day (reception_id, business_date, status)"
            + " values (?, '2030-01-10', 'OPEN')",
        reception);
    long roomA = room(building, "A", k1);
    long roomB = room(building, "B", k2);
    return new Fx(k1, k2, bed(roomA, 1), bed(roomA, 2), bed(roomB, 1), bed(roomB, 2));
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

  private long room(long building, String number, long category) {
    return new TransactionTemplate(tx)
        .execute(s -> {
          long id =
              jdbc.queryForObject(
                  "insert into room (building_id, number, category_id, regular_beds, extra_beds)"
                      + " values (?, ?, ?, 2, 0) returning id",
                  Long.class,
                  building,
                  number,
                  category);
          for (int i = 1; i <= 2; i++) {
            jdbc.update(
                "insert into bed (room_id, bed_number, type) values (?, ?, 'REGULAR')", id, i);
          }
          return id;
        });
  }

  private long bed(long room, int number) {
    return jdbc.queryForObject(
        "select id from bed where room_id = ? and bed_number = ?", Long.class, room, number);
  }

  private long card(Fx fx, String arrival, String departure, boolean exclusive, Long party) {
    long payer =
        jdbc.queryForList("select id from payer limit 1", Long.class).stream()
            .findFirst()
            .orElseGet(() ->
                jdbc.queryForObject(
                    "insert into payer (code, name, type) values ('MVP', 'Platca', 'SELF')"
                        + " returning id",
                    Long.class));
    long id =
        jdbc.queryForObject(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id, exclusive_room_use, party_id)"
                + " values (?, 'RESERVED', ?::date, ?::date, ?, ?, ?, ?) returning id",
            Long.class,
            "MV" + System.nanoTime(),
            arrival,
            departure,
            fx.category(),
            payer,
            exclusive,
            party);
    long client =
        jdbc.queryForObject(
            "insert into client (first_name, last_name, birth_date)"
                + " values ('Test', 'Hosť', '1970-01-01') returning id",
            Long.class);
    jdbc.update(
        "insert into reservation_guest (reservation_id, client_id, primary_guest)"
            + " values (?, ?, true)",
        id,
        client);
    return id;
  }

  private long card(Fx fx, String arrival, String departure) {
    return card(fx, arrival, departure, false, null);
  }

  private long stay(Fx fx, long card, long bed, String from, String to) {
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

  private RequestPostProcessor receptionist() {
    return user("move.recepcna", "RECEPTIONIST");
  }

  private RequestPostProcessor manager() {
    return user("move.veduca", "RECEPTION_MANAGER");
  }

  private RequestPostProcessor user(String username, String role) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, PASSWORD, false, role);
    }
    return TestUsers.as(jdbc, username);
  }

  private MvcResult send(RequestPostProcessor who, String path, String body) throws Exception {
    return mvc.perform(
            post("/api/stays" + path)
                .with(csrf())
                .with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private MvcResult patchStay(RequestPostProcessor who, long id, String body) throws Exception {
    return mvc.perform(
            patch("/api/stays/" + id)
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

  private long bedOf(long stay) {
    return jdbc.queryForObject("select bed_id from stay where id = ?", Long.class, stay);
  }

  private String period(long stay) {
    return jdbc.queryForObject(
        "select date_from || '/' || date_to from stay where id = ?", String.class, stay);
  }

  private String auditOf(MvcResult r) {
    String eventId = r.getResponse().getHeader("X-Audit-Event-Id");
    assertThat(eventId).isNotBlank();
    return jdbc.queryForObject(
        "select operation || '/' || outcome from audit_event where id = ?",
        String.class,
        Long.valueOf(eventId));
  }

  private static String move(long bed, String fromDate, String... confirm) {
    return "{\"targetBedId\":%d,%s\"confirm\":[%s]}"
        .formatted(
            bed,
            fromDate == null ? "" : "\"fromDate\":\"" + fromDate + "\",",
            String.join(",", java.util.Arrays.stream(confirm).map(c -> "\"" + c + "\"").toList()));
  }

  private static String item(long stay, long bed) {
    return "{\"stayId\":%d,\"targetBedId\":%d}".formatted(stay, bed);
  }

  @Test
  void movesWholeStayAndReturnsUpdatedStripsOfBothRooms() throws Exception {
    Fx fx = fx();
    long stay = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");

    MvcResult r = send(receptionist(), "/" + stay + "/move", move(fx.a2(), null));

    assertThat(status(r)).isEqualTo(200);
    assertThat(bedOf(stay)).isEqualTo(fx.a2());
    assertThat(auditOf(r)).isEqualTo("STAY_MOVE/SUCCESS");
    List<Integer> stayBars = JsonPath.read(json(r), "$.rooms[*].bars[?(@.type == 'STAY')].stayId");
    assertThat(stayBars).containsExactly((int) stay);
    assertThat(JsonPath.<List<?>>read(json(r), "$.rooms")).hasSize(1);
  }

  @Test
  void splitsStayFromDayOntoTargetBedInOtherRoom() throws Exception {
    Fx fx = fx();
    long stay = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");

    MvcResult r = send(receptionist(), "/" + stay + "/move", move(fx.b1(), "2030-01-13"));

    assertThat(status(r)).isEqualTo(200);
    assertThat(bedOf(stay)).isEqualTo(fx.a1());
    assertThat(period(stay)).isEqualTo("2030-01-10/2030-01-13");
    assertThat(
            jdbc.queryForObject(
                "select date_from || '/' || date_to from stay where bed_id = ?",
                String.class,
                fx.b1()))
        .isEqualTo("2030-01-13/2030-01-17");
    // Nové lôžko je v izbe inej kategórie: kategória pre cenu nasleduje izbu (FOLLOW_ROOM).
    assertThat(
            jdbc.queryForObject(
                "select price_category_id from stay where bed_id = ?", Long.class, fx.b1()))
        .isEqualTo(fx.otherCategory());
    assertThat(JsonPath.<List<?>>read(json(r), "$.rooms")).hasSize(2);
  }

  @Test
  void splitDayMustBeInsideStay() throws Exception {
    Fx fx = fx();
    long stay = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");

    assertThat(status(send(receptionist(), "/" + stay + "/move", move(fx.a2(), "2030-01-10"))))
        .isEqualTo(400);
    assertThat(status(send(receptionist(), "/" + stay + "/move", move(fx.a2(), "2030-01-17"))))
        .isEqualTo(400);
    assertThat(bedOf(stay)).isEqualTo(fx.a1());
  }

  @Test
  void backdatedMoveNeedsBackdatePermission() throws Exception {
    Fx fx = fx();
    long stay = stay(fx, card(fx, "2030-01-05", "2030-01-17"), fx.a1(), "2030-01-05", "2030-01-17");

    MvcResult whole = send(receptionist(), "/" + stay + "/move", move(fx.a2(), null));
    assertThat(status(whole)).isEqualTo(422);
    assertThat(code(whole)).isEqualTo("PAST_CHANGE");
    MvcResult fromPast = send(receptionist(), "/" + stay + "/move", move(fx.a2(), "2030-01-08"));
    assertThat(code(fromPast)).isEqualTo("PAST_CHANGE");
    assertThat(bedOf(stay)).isEqualTo(fx.a1());

    // Od obchodného dňa už presun možný je; spätne len s oprávnením STAY_BACKDATE.
    assertThat(status(send(receptionist(), "/" + stay + "/move", move(fx.a2(), "2030-01-12"))))
        .isEqualTo(200);
    assertThat(status(send(manager(), "/" + stay + "/move", move(fx.b1(), null)))).isEqualTo(200);
  }

  @Test
  void staleVersionIsRejectedWithStaleData() throws Exception {
    Fx fx = fx();
    long stay = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");
    long version = jdbc.queryForObject("select version from stay where id = ?", Long.class, stay);
    jdbc.update("update stay set version = version + 1 where id = ?", stay);

    MvcResult r =
        send(
            receptionist(),
            "/" + stay + "/move",
            "{\"targetBedId\":%d,\"version\":%d}".formatted(fx.a2(), version));

    assertThat(status(r)).isEqualTo(409);
    assertThat(code(r)).isEqualTo("STALE_DATA");
    assertThat(bedOf(stay)).isEqualTo(fx.a1());
    MvcResult resize =
        patchStay(
            receptionist(), stay, "{\"to\":\"2030-01-18\",\"version\":%d}".formatted(version));
    assertThat(status(resize)).isEqualTo(409);
    assertThat(code(resize)).isEqualTo("STALE_DATA");
  }

  @Test
  void extensionMovesCardDepartureButShorteningDoesNot() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-17");
    long stay = stay(fx, card, fx.a1(), "2030-01-10", "2030-01-17");

    MvcResult extend = patchStay(receptionist(), stay, "{\"to\":\"2030-01-20\",\"version\":0}");
    assertThat(status(extend)).isEqualTo(200);
    assertThat(auditOf(extend)).isEqualTo("STAY_RESIZE/SUCCESS");
    assertThat(period(stay)).isEqualTo("2030-01-10/2030-01-20");
    assertThat(
            jdbc.queryForObject(
                "select departure_date::text from reservation where id = ?", String.class, card))
        .isEqualTo("2030-01-20");

    long version = jdbc.queryForObject("select version from stay where id = ?", Long.class, stay);
    MvcResult shorten =
        patchStay(
            receptionist(), stay, "{\"to\":\"2030-01-14\",\"version\":%d}".formatted(version));
    assertThat(status(shorten)).isEqualTo(200);
    assertThat(period(stay)).isEqualTo("2030-01-10/2030-01-14");
    assertThat(
            jdbc.queryForObject(
                "select departure_date::text from reservation where id = ?", String.class, card))
        .isEqualTo("2030-01-20");
  }

  @Test
  void extensionIntoOccupiedBedIsRejectedAndLockCanBeToggled() throws Exception {
    Fx fx = fx();
    long stay = stay(fx, card(fx, "2030-01-10", "2030-01-15"), fx.a1(), "2030-01-10", "2030-01-15");
    stay(fx, card(fx, "2030-01-15", "2030-01-20"), fx.a1(), "2030-01-15", "2030-01-20");

    MvcResult clash = patchStay(receptionist(), stay, "{\"to\":\"2030-01-16\",\"version\":0}");
    assertThat(status(clash)).isEqualTo(409);
    assertThat(code(clash)).isEqualTo("BED_OCCUPIED");
    assertThat(period(stay)).isEqualTo("2030-01-10/2030-01-15");

    MvcResult lock = patchStay(receptionist(), stay, "{\"locked\":true,\"version\":0}");
    assertThat(status(lock)).isEqualTo(200);
    assertThat(jdbc.queryForObject("select locked from stay where id = ?", Boolean.class, stay))
        .isTrue();
    assertThat(
            JsonPath.<List<Boolean>>read(
                json(lock), "$.rooms[*].bars[?(@.stayId == " + stay + ")].locked"))
        .containsExactly(true);
  }

  @Test
  void swapsTwoClientsBetweenFullRooms() throws Exception {
    Fx fx = fx();
    long x = stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.a1(), "2030-01-12", "2030-01-18");
    long y = stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.b1(), "2030-01-12", "2030-01-18");
    stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.a2(), "2030-01-12", "2030-01-18");
    stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.b2(), "2030-01-12", "2030-01-18");

    MvcResult r = send(receptionist(), "/swap", "{\"stayIdA\":%d,\"stayIdB\":%d}".formatted(x, y));

    assertThat(status(r)).isEqualTo(200);
    assertThat(bedOf(x)).isEqualTo(fx.b1());
    assertThat(bedOf(y)).isEqualTo(fx.a1());
    assertThat(auditOf(r)).isEqualTo("STAY_SWAP/SUCCESS");
    assertThat(JsonPath.<List<?>>read(json(r), "$.rooms")).hasSize(2);
  }

  @Test
  void swapFailsWhenBedIsNotFreeForWholeOtherStay() throws Exception {
    Fx fx = fx();
    long x = stay(fx, card(fx, "2030-01-12", "2030-01-14"), fx.a1(), "2030-01-12", "2030-01-14");
    long y = stay(fx, card(fx, "2030-01-12", "2030-01-20"), fx.b1(), "2030-01-12", "2030-01-20");
    // Lôžko A1 je po odchode X obsadené niekým iným, takže Y by sa naň nezmestil.
    stay(fx, card(fx, "2030-01-14", "2030-01-20"), fx.a1(), "2030-01-14", "2030-01-20");

    MvcResult r = send(receptionist(), "/swap", "{\"stayIdA\":%d,\"stayIdB\":%d}".formatted(x, y));

    assertThat(status(r)).isEqualTo(409);
    assertThat(code(r)).isEqualTo("BED_OCCUPIED");
    assertThat(bedOf(x)).isEqualTo(fx.a1());
    assertThat(bedOf(y)).isEqualTo(fx.b1());
  }

  @Test
  void batchWithCollidingStepsButValidFinalStateIsWrittenInOneAudit() throws Exception {
    Fx fx = fx();
    long x = stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.a1(), "2030-01-12", "2030-01-18");
    long y = stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.a2(), "2030-01-12", "2030-01-18");
    int before =
        jdbc.queryForObject(
            "select count(*) from audit_event where operation = 'STAY_MOVE_BATCH'", Integer.class);

    // X sa najprv ocitne na lôžku, ktoré ešte drží Y; Y však odíde na voľné lôžko B1.
    MvcResult r =
        send(receptionist(), "/moves:batch", "[" + item(x, fx.a2()) + "," + item(y, fx.b1()) + "]");

    assertThat(status(r)).isEqualTo(200);
    assertThat(bedOf(x)).isEqualTo(fx.a2());
    assertThat(bedOf(y)).isEqualTo(fx.b1());
    assertThat(auditOf(r)).isEqualTo("STAY_MOVE_BATCH/SUCCESS");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'STAY_MOVE_BATCH'",
                Integer.class))
        .isEqualTo(before + 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_change where audit_event_id = ? and entity_type = 'stay'",
                Integer.class,
                Long.valueOf(r.getResponse().getHeader("X-Audit-Event-Id"))))
        .isEqualTo(2);
  }

  @Test
  void batchWithOneInvalidMoveWritesNothing() throws Exception {
    Fx fx = fx();
    long x = stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.a1(), "2030-01-12", "2030-01-18");
    long y = stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.a2(), "2030-01-12", "2030-01-18");
    stay(fx, card(fx, "2030-01-12", "2030-01-18"), fx.b2(), "2030-01-12", "2030-01-18");

    MvcResult r =
        send(receptionist(), "/moves:batch", "[" + item(x, fx.b1()) + "," + item(y, fx.b2()) + "]");

    assertThat(status(r)).isEqualTo(409);
    assertThat(code(r)).isEqualTo("BED_OCCUPIED");
    assertThat(bedOf(x)).isEqualTo(fx.a1());
    assertThat(bedOf(y)).isEqualTo(fx.a2());
  }

  @Test
  void moveOntoHeldBedNeedsConfirmationAndCancelsExclusiveUseInSameAudit() throws Exception {
    Fx fx = fx();
    long holder = card(fx, "2030-01-10", "2030-01-17", true, null);
    stay(fx, holder, fx.b1(), "2030-01-10", "2030-01-17");
    long x = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");

    MvcResult warn = send(receptionist(), "/" + x + "/move", move(fx.b2(), null));
    assertThat(status(warn)).isEqualTo(409);
    assertThat(code(warn)).isEqualTo("CONFIRMATION_REQUIRED");
    assertThat(JsonPath.<List<String>>read(json(warn), "$.warnings[*].code"))
        .contains("RELEASES_HELD_BED");
    assertThat(bedOf(x)).isEqualTo(fx.a1());

    MvcResult ok =
        send(receptionist(), "/" + x + "/move", move(fx.b2(), null, "RELEASES_HELD_BED"));
    assertThat(status(ok)).isEqualTo(200);
    assertThat(bedOf(x)).isEqualTo(fx.b2());
    assertThat(
            jdbc.queryForObject(
                "select exclusive_room_use from reservation where id = ?", Boolean.class, holder))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_change where audit_event_id = ?"
                    + " and entity_type = 'reservation'",
                Integer.class,
                Long.valueOf(ok.getResponse().getHeader("X-Audit-Event-Id"))))
        .isEqualTo(1);
  }

  @Test
  void lockedStayNeedsPermissionAndConfirmation() throws Exception {
    Fx fx = fx();
    long x = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");
    jdbc.update("update stay set locked = true where id = ?", x);

    MvcResult denied = send(receptionist(), "/" + x + "/move", move(fx.a2(), null));
    assertThat(status(denied)).isEqualTo(422);
    assertThat(code(denied)).isEqualTo("MISSING_PERMISSION");

    MvcResult warn = send(manager(), "/" + x + "/move", move(fx.a2(), null));
    assertThat(status(warn)).isEqualTo(409);
    assertThat(JsonPath.<List<String>>read(json(warn), "$.warnings[*].code"))
        .contains("STAY_LOCKED");
    assertThat(status(send(manager(), "/" + x + "/move", move(fx.a2(), null, "STAY_LOCKED"))))
        .isEqualTo(200);
  }

  @Test
  void movingOnlyOnePartyMemberNeedsConfirmation() throws Exception {
    Fx fx = fx();
    long party =
        jdbc.queryForObject(
            "insert into reservation_party default values returning id", Long.class);
    long x =
        stay(
            fx,
            card(fx, "2030-01-10", "2030-01-17", false, party),
            fx.a1(),
            "2030-01-10",
            "2030-01-17");
    stay(
        fx,
        card(fx, "2030-01-10", "2030-01-17", false, party),
        fx.a2(),
        "2030-01-10",
        "2030-01-17");

    MvcResult warn = send(receptionist(), "/" + x + "/move", move(fx.b1(), null));
    assertThat(status(warn)).isEqualTo(409);
    assertThat(JsonPath.<List<String>>read(json(warn), "$.warnings[*].code"))
        .contains("PARTY_MEMBER_MOVED");

    assertThat(
            status(
                send(receptionist(), "/" + x + "/move", move(fx.b1(), null, "PARTY_MEMBER_MOVED"))))
        .isEqualTo(200);
    assertThat(bedOf(x)).isEqualTo(fx.b1());
  }

  @Test
  void keepBookedModeKeepsBookedCategoryAfterMove() throws Exception {
    Fx fx = fx();
    long card = card(fx, "2030-01-10", "2030-01-17");
    jdbc.update("update reservation set price_category_mode = 'KEEP_BOOKED' where id = ?", card);
    long x = stay(fx, card, fx.a1(), "2030-01-10", "2030-01-17");

    assertThat(status(send(receptionist(), "/" + x + "/move", move(fx.b1(), null)))).isEqualTo(200);
    assertThat(
            jdbc.queryForObject("select price_category_id from stay where id = ?", Long.class, x))
        .isEqualTo(fx.category());
  }

  @Test
  void movePermissionIsRequired() throws Exception {
    Fx fx = fx();
    long x = stay(fx, card(fx, "2030-01-10", "2030-01-17"), fx.a1(), "2030-01-10", "2030-01-17");

    assertThat(status(send(user("move.lekar", "DOCTOR"), "/" + x + "/move", move(fx.a2(), null))))
        .isEqualTo(403);
    assertThat(bedOf(x)).isEqualTo(fx.a1());
  }
}
