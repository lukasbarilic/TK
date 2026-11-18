package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

/** Počítadlo zmien recepcie (reception_change_seq) a podmienené čítanie (ETag, 304). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ChangeSeqEtagIntegrationTest {

  private static int seq;

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;
  @Autowired PlatformTransactionManager tx;

  private record Fx(long receptionA, long receptionB, long roomA, long bedA, long card) {}

  private Fx fx() {
    int n = ++seq;
    long centre = id("insert into centre (code, name) values ('ES" + n + "', 'Etag') returning id");
    long recA = id("insert into reception (code, name) values ('EA" + n + "', 'A') returning id");
    long recB = id("insert into reception (code, name) values ('EB" + n + "', 'B') returning id");
    long buA = building(centre, recA, "A");
    building(centre, recB, "B");
    long base = id("insert into base_category (name) values ('EK" + n + "') returning id");
    long cat =
        id(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values ("
                + centre
                + ", "
                + base
                + ", 'K2', 'Dvojlôžková', 2) returning id");
    long payer =
        id(
            "insert into payer (code, name, type) values ('EP"
                + n
                + "', 'Platca', 'SELF') returning id");
    long room =
        id(
            "insert into room (building_id, number, category_id, regular_beds) values ("
                + buA
                + ", '1', "
                + cat
                + ", 2) returning id");
    long bed =
        id(
            "insert into bed (room_id, bed_number, type) values ("
                + room
                + ", 1, 'REGULAR') returning id");
    long card =
        id(
            "insert into reservation (reservation_number, status, arrival_date, departure_date,"
                + " booked_category_id, payer_id) values ('ES"
                + System.nanoTime()
                + "', 'RESERVED', '2030-01-05', '2030-01-14', "
                + cat
                + ", "
                + payer
                + ") returning id");
    return new Fx(recA, recB, room, bed, card);
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

  private long seqOf(long reception) {
    var found =
        jdbc.queryForList(
            "select seq from reception_change_seq where reception_id = ?", Long.class, reception);
    return found.isEmpty() ? 0 : found.getFirst();
  }

  private void insertStay(Fx fx) {
    jdbc.update(
        "insert into stay (reservation_id, bed_id, date_from, date_to, price_category_id)"
            + " select ?, ?, '2030-01-05', '2030-01-14', booked_category_id from reservation where id = ?",
        fx.card(),
        fx.bedA(),
        fx.card());
  }

  private RequestPostProcessor user(String username) {
    TestUsers.create(jdbc, encoder, username, "Heslo-na-test-1", false, "RECEPTIONIST");
    return TestUsers.as(jdbc, username);
  }

  @Test
  void stayWriteBumpsOnlyAffectedReception() {
    Fx fx = fx();
    long a = seqOf(fx.receptionA());
    long b = seqOf(fx.receptionB());

    insertStay(fx);

    assertThat(seqOf(fx.receptionA())).isGreaterThan(a);
    assertThat(seqOf(fx.receptionB())).isEqualTo(b);
  }

  @Test
  void blockAndBusinessDayWritesBumpSeq() {
    Fx fx = fx();
    long reason = id("insert into block_reason (name) values ('Dôvod " + seq + "') returning id");
    long a = seqOf(fx.receptionA());
    jdbc.update(
        "insert into room_block (room_id, reason_id, date_from, date_to) values (?, ?, '2030-02-01', '2030-02-03')",
        fx.roomA(),
        reason);
    long afterBlock = seqOf(fx.receptionA());
    assertThat(afterBlock).isGreaterThan(a);

    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, '2030-01-12', 'OPEN')",
        fx.receptionA());
    jdbc.update(
        "update business_day set status = 'CLOSED', closed_at = now() where reception_id = ?",
        fx.receptionA());
    assertThat(seqOf(fx.receptionA())).isGreaterThan(afterBlock);
  }

  @Test
  void rolledBackTransactionDoesNotBumpSeq() {
    Fx fx = fx();
    long a = seqOf(fx.receptionA());

    new TransactionTemplate(tx)
        .executeWithoutResult(s -> {
          insertStay(fx);
          s.setRollbackOnly();
        });

    assertThat(seqOf(fx.receptionA())).isEqualTo(a);
  }

  @Test
  void planReturns304WithoutAuditUntilChanged() throws Exception {
    Fx fx = fx();
    RequestPostProcessor who = user("etag.plan." + seq);
    String url =
        "/api/accommodation-plan?from=2030-01-10&to=2030-01-20&receptionId=" + fx.receptionA();

    MvcResult first = mvc.perform(get(url).with(who)).andReturn();
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    String etag = first.getResponse().getHeader("ETag");
    assertThat(etag).isNotBlank();
    long audits = planViewAudits();

    MvcResult cached = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(cached.getResponse().getStatus()).isEqualTo(304);
    assertThat(cached.getResponse().getContentAsString()).isEmpty();
    assertThat(planViewAudits()).isEqualTo(audits);

    insertStay(fx);

    MvcResult changed = mvc.perform(get(url).with(who).header("If-None-Match", etag)).andReturn();
    assertThat(changed.getResponse().getStatus()).isEqualTo(200);
    assertThat(changed.getResponse().getHeader("ETag")).isNotEqualTo(etag);
    assertThat(planViewAudits()).isEqualTo(audits + 1);
  }

  @Test
  void planEtagDiffersPerUserAndFilter() throws Exception {
    Fx fx = fx();
    String url =
        "/api/accommodation-plan?from=2030-01-10&to=2030-01-20&receptionId=" + fx.receptionA();
    String e1 =
        mvc.perform(get(url).with(user("etag.u1." + seq)))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    String e2 =
        mvc.perform(get(url).with(user("etag.u2." + seq)))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    String e3 =
        mvc.perform(get(url + "&freeOnly=true").with(TestUsers.as(jdbc, "etag.u1." + seq)))
            .andReturn()
            .getResponse()
            .getHeader("ETag");
    assertThat(e1).isNotEqualTo(e2).isNotEqualTo(e3);
  }

  @Test
  void catalogSupportsEtagAnd304() throws Exception {
    RequestPostProcessor who = user("etag.catalog." + (++seq));
    for (String path :
        new String[] {"centres", "receptions", "buildings", "rooms", "room-categories", "payers"}) {
      MvcResult first = mvc.perform(get("/api/catalog/" + path).with(who)).andReturn();
      assertThat(first.getResponse().getStatus()).as(path).isEqualTo(200);
      String etag = first.getResponse().getHeader("ETag");
      assertThat(etag).as(path).isNotBlank();
      MvcResult second =
          mvc.perform(get("/api/catalog/" + path).with(who).header("If-None-Match", etag))
              .andReturn();
      assertThat(second.getResponse().getStatus()).as(path).isEqualTo(304);
      assertThat(second.getResponse().getContentAsString()).as(path).isEmpty();
    }
  }

  private long planViewAudits() {
    return jdbc.queryForObject(
        "select count(*) from audit_event where operation = 'PLAN_VIEW'", Long.class);
  }
}
