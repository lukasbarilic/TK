package sk.therasuite.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sk.therasuite.TestcontainersConfig;

/** Profil test (prázdna DB): schéma modulu pricing, seed vekových skupín a rola Obchodné oddelenie. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class PricingMigrationIntegrationTest {

  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationCreatesAllPricingTablesAndPromoCode() {
    List<String> tables =
        jdbc.queryForList(
            "select table_name from information_schema.tables where table_schema = 'public'",
            String.class);
    assertThat(tables)
        .contains(
            "price_list",
            "price_season",
            "price_rate",
            "occupancy_modifier",
            "age_group",
            "package",
            "package_item",
            "price_surcharge",
            "price_discount",
            "price_discount_target",
            "payer_coverage",
            "reservation_package",
            "reservation_guest_coverage",
            "reservation_price",
            "reservation_price_line",
            "reservation_price_day");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns"
                    + " where table_name = 'reservation' and column_name = 'promo_code'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void ageGroupsAreSeeded() {
    assertThat(
            jdbc.queryForList(
                "select code || ':' || age_from || ':' || coalesce(age_to::text, '') from age_group"
                    + " order by code"))
        .extracting(row -> row.values().iterator().next())
        .containsExactly("CHILD:0:17", "SENIOR:71:");
  }

  @Test
  void priceRateKeyTreatsNullsAsEqual() {
    long listId =
        jdbc.queryForObject(
            "insert into price_list (name, valid_from) values ('Test', date '2026-01-01') returning id",
            Long.class);
    long seasonId =
        jdbc.queryForObject(
            "insert into price_season (price_list_id, code, name, date_from, date_to)"
                + " values (?, 'S', 'S', date '2026-01-01', date '2026-12-31') returning id",
            Long.class,
            listId);
    long centreId =
        jdbc.queryForObject(
            "insert into centre (code, name) values ('PRC', 'Cenotvorba test') returning id",
            Long.class);
    long baseId =
        jdbc.queryForObject("select id from base_category limit 1", Long.class);
    long categoryId =
        jdbc.queryForObject(
            "insert into room_category (centre_id, base_category_id, code, name, bed_count)"
                + " values (?, ?, 'PRC1', 'Test', 2) returning id",
            Long.class,
            centreId,
            baseId);
    String insert =
        "insert into price_rate (price_season_id, room_category_id, price_type, amount)"
            + " values (?, ?, 'PER_BED', 50)";
    jdbc.update(insert, seasonId, categoryId);

    assertThatThrownBy(() -> jdbc.update(insert, seasonId, categoryId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void salesRoleHasOnlyPricingPermissions() {
    List<String> permissions =
        jdbc.queryForList(
            "select rp.permission_code from role_permission rp join role r on r.id = rp.role_id"
                + " where r.code = 'SALES' order by 1",
            String.class);
    assertThat(permissions).containsExactly("PRICE_LIST_EDIT", "PRICE_VIEW");
    assertThat(permissions)
        .doesNotContain("RESERVATION_VIEW", "RESERVATION_EDIT", "CLIENT_VIEW", "PLAN_VIEW")
        .noneMatch(code -> code.startsWith("GUEST_LOCATION"));
  }

  @Test
  void pricingPermissionsAreAssignedToReceptionRoles() {
    assertThat(permissionsOf("RECEPTIONIST"))
        .contains("PRICE_VIEW", "PRICE_EDIT", "PRICE_OVERRIDE")
        .doesNotContain("PRICE_LIST_EDIT");
    assertThat(permissionsOf("RECEPTION_MANAGER"))
        .contains("PRICE_VIEW", "PRICE_EDIT", "PRICE_OVERRIDE", "PRICE_LIST_EDIT");
    assertThat(permissionsOf("ADMIN")).contains("PRICE_VIEW", "PRICE_LIST_EDIT");
    assertThat(
            jdbc.queryForObject(
                "select rp.limit_value from role_permission rp join role r on r.id = rp.role_id"
                    + " where r.code = 'RECEPTIONIST' and rp.permission_code = 'PRICE_OVERRIDE'",
                Integer.class))
        .isEqualTo(10);
  }

  private List<String> permissionsOf(String role) {
    return jdbc.queryForList(
        "select rp.permission_code from role_permission rp join role r on r.id = rp.role_id"
            + " where r.code = ?",
        String.class,
        role);
  }
}
