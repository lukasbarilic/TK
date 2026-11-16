package sk.therasuite.blocking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.blocking.application.RoomBlockService;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RoomBlockConstraintIntegrationTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired RoomBlockService service;

  @Test
  void exclusionAndDateChecksWork() {
    jdbc.update("insert into centre (code, name) values ('BLK', 'Test')");
    jdbc.update("insert into reception (code, name) values ('BLK', 'Test')");
    jdbc.update("insert into base_category (name) values ('BLK')");
    jdbc.update("insert into block_reason (name) values ('BLK')");
    jdbc.update(
        "insert into building (centre_id, reception_id, code, name) values ((select id from centre where code='BLK'), (select id from reception where code='BLK'), 'B', 'Test')");
    jdbc.update(
        "insert into room_category (centre_id, base_category_id, code, name, bed_count) values ((select id from centre where code='BLK'), (select id from base_category where name='BLK'), 'C', 'Test', 1)");
    Long cat = jdbc.queryForObject("select max(id) from room_category", Long.class);
    Long building = jdbc.queryForObject("select max(id) from building", Long.class);
    Long room =
        jdbc.queryForObject(
            "insert into room (building_id, number, category_id, regular_beds) values (?, '1', ?, 1) returning id",
            Long.class,
            building,
            cat);
    Long other =
        jdbc.queryForObject(
            "insert into room (building_id, number, category_id, regular_beds) values (?, '2', ?, 1) returning id",
            Long.class,
            building,
            cat);
    Long reason = jdbc.queryForObject("select id from block_reason where name='BLK'", Long.class);

    insert(room, reason, "2026-03-10", "2026-03-15");

    // prekrytie tej istej izby sa odmietne
    assertThatThrownBy(() -> insert(room, reason, "2026-03-14", "2026-03-20"))
        .isInstanceOf(DataAccessException.class);
    // nadväzujúci interval [) je v poriadku; iná izba tiež
    insert(room, reason, "2026-03-15", "2026-03-20");
    insert(other, reason, "2026-03-10", "2026-03-15");
    // date_from < date_to
    assertThatThrownBy(() -> insert(room, reason, "2026-04-05", "2026-04-05"))
        .isInstanceOf(DataAccessException.class);

    assertThat(service.find(LocalDate.parse("2026-03-14"), LocalDate.parse("2026-03-16"), room))
        .hasSize(2);
    assertThat(service.find(LocalDate.parse("2026-03-15"), LocalDate.parse("2026-03-16"), room))
        .hasSize(1);
  }

  private void insert(Long room, Long reason, String from, String to) {
    jdbc.update(
        "insert into room_block (room_id, reason_id, date_from, date_to) values (?, ?, ?::date, ?::date)",
        room,
        reason,
        from,
        to);
  }
}
