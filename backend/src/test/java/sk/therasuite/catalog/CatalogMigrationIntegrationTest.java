package sk.therasuite.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import sk.therasuite.TestcontainersConfig;

/** Profil test: prázdny katalóg, kontrola schémy a obmedzení (bez demo dát). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class CatalogMigrationIntegrationTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate tx;
  @Autowired MockMvc mvc;

  @Test
  void migrationCreatesAllTables() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);

    assertThat(tables)
        .contains(
            "centre",
            "reception",
            "building",
            "base_category",
            "room_category",
            "department",
            "room",
            "bed",
            "room_feature",
            "room_feature_assignment",
            "payer",
            "block_reason",
            "room_block");
  }

  @Test
  void auditColumnsExistAndUserColumnsAreNullableBigintWithoutForeignKey() {
    List<String> nullableBigint =
        jdbc.queryForList(
            """
            select column_name from information_schema.columns
            where table_name = 'room' and column_name in ('created_by', 'updated_by')
              and data_type = 'bigint' and is_nullable = 'YES'
            """,
            String.class);
    Integer foreignKeys =
        jdbc.queryForObject(
            """
            select count(*) from information_schema.key_column_usage k
            join information_schema.table_constraints t using (constraint_name, table_schema)
            where t.constraint_type = 'FOREIGN KEY' and k.column_name in ('created_by', 'updated_by')
            """,
            Integer.class);

    assertThat(nullableBigint).containsExactlyInAnyOrder("created_by", "updated_by");
    assertThat(foreignKeys).isZero();
  }

  @Test
  void bedNumberIsUniquePerRoom() {
    Long roomId = insertRoom("UQ1", 1, 0);
    insertBed(roomId, 1, "REGULAR");

    assertThatThrownBy(() -> insertBed(roomId, 1, "REGULAR"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void extraBedCountMustMatchRoom() {
    Long roomId = insertRoom("EB1", 1, 0);
    insertBed(roomId, 1, "REGULAR");

    // room.extra_beds = 1 bez prístelky -> commit transakcie zlyhá
    assertThatThrownBy(() ->
            tx.executeWithoutResult(
                s -> jdbc.update("update room set extra_beds = 1 where id = ?", roomId)))
        .isInstanceOf(DataAccessException.class);

    tx.executeWithoutResult(s -> {
      jdbc.update("update room set extra_beds = 1 where id = ?", roomId);
      insertBed(roomId, 2, "EXTRA");
    });
    assertThat(
            jdbc.queryForObject(
                "select count(*) from bed where room_id = ? and type = 'EXTRA'",
                Integer.class,
                roomId))
        .isEqualTo(1);
  }

  @Test
  void emptyCatalogEndpointsReturnEmptyLists() throws Exception {
    mvc.perform(get("/api/catalog/centres")).andExpect(status().isOk());
    mvc.perform(get("/api/catalog/rooms/999999")).andExpect(status().isNotFound());
    mvc.perform(get("/api/room-blocks?from=2026-02-01&to=2026-01-01"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/room-blocks?roomId=-1")).andExpect(jsonPath("$").isEmpty());
  }

  private Long insertRoom(String number, int regular, int extra) {
    jdbc.update(
        "insert into centre (code, name) values (?, 'Test') on conflict do nothing", "T" + number);
    Long centreId =
        jdbc.queryForObject("select id from centre where code = ?", Long.class, "T" + number);
    jdbc.update("insert into reception (code, name) values (?, 'Test')", "R" + number);
    Long receptionId =
        jdbc.queryForObject("select id from reception where code = ?", Long.class, "R" + number);
    jdbc.update(
        "insert into building (centre_id, reception_id, code, name) values (?, ?, 'B', 'Test')",
        centreId,
        receptionId);
    Long buildingId = jdbc.queryForObject("select max(id) from building", Long.class);
    jdbc.update("insert into base_category (name) values (?)", "BC" + number);
    Long baseId = jdbc.queryForObject("select max(id) from base_category", Long.class);
    jdbc.update(
        "insert into room_category (centre_id, base_category_id, code, name, bed_count) values (?, ?, 'C', 'Test', 1)",
        centreId,
        baseId);
    Long categoryId = jdbc.queryForObject("select max(id) from room_category", Long.class);
    return jdbc.queryForObject(
        """
        insert into room (building_id, number, category_id, regular_beds, extra_beds)
        values (?, '1', ?, ?, ?) returning id
        """,
        Long.class,
        buildingId,
        categoryId,
        regular,
        extra);
  }

  private void insertBed(Long roomId, int number, String type) {
    jdbc.update(
        "insert into bed (room_id, bed_number, type) values (?, ?, ?)", roomId, number, type);
  }
}
