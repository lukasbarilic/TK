package sk.therasuite.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.shared.infrastructure.DemoDataLoader;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@Import(TestcontainersConfig.class)
class DemoDataIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired DemoDataLoader loader;
  @Autowired ApplicationArguments args;

  private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
    return sk.therasuite.TestUsers.as(jdbc, "recepcna.hlavna");
  }

  @Test
  void demoDataIsLoadedWith431RoomsAndBeds() throws Exception {
    mvc.perform(get("/api/catalog/rooms").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(431)))
        .andExpect(jsonPath("$[?(@.beds.length() == 0)]").isEmpty());

    Integer rooms = jdbc.queryForObject("select count(*) from room", Integer.class);
    Integer extraMismatch =
        jdbc.queryForObject(
            """
            select count(*) from room r
            where r.extra_beds <> (select count(*) from bed b where b.room_id = r.id and b.type = 'EXTRA')
               or r.regular_beds <> (select count(*) from bed b where b.room_id = r.id and b.type = 'REGULAR')
            """,
            Integer.class);
    assertThat(rooms).isEqualTo(431);
    assertThat(extraMismatch).isZero();
  }

  @Test
  void demoStaysAreLoadedWithBedsResolvedFromRoomAndBedNumber() {
    Integer stays = jdbc.queryForObject("select count(*) from stay", Integer.class);
    Integer unresolved =
        jdbc.queryForObject(
            "select count(*) from stay s left join bed b on b.id = s.bed_id where b.id is null",
            Integer.class);
    assertThat(stays).isEqualTo(162);
    assertThat(unresolved).isZero();
  }

  @Test
  void roomDetailIncludesExtraBed() throws Exception {
    mvc.perform(get("/api/catalog/rooms/1").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.number").value("101"))
        .andExpect(jsonPath("$.beds", hasSize(3)))
        .andExpect(jsonPath("$.beds[2].type").value("EXTRA"));
  }

  @Test
  void otherEndpointsServeDemoData() throws Exception {
    mvc.perform(get("/api/catalog/centres").with(user())).andExpect(jsonPath("$", hasSize(1)));
    mvc.perform(get("/api/catalog/receptions").with(user())).andExpect(jsonPath("$", hasSize(2)));
    mvc.perform(get("/api/catalog/buildings").with(user())).andExpect(jsonPath("$", hasSize(6)));
    mvc.perform(get("/api/catalog/rooms?buildingId=1").with(user())).andExpect(status().isOk());
    mvc.perform(get("/api/catalog/room-categories").with(user()))
        .andExpect(jsonPath("$", hasSize(8)));
    mvc.perform(get("/api/catalog/payers").with(user())).andExpect(jsonPath("$", hasSize(4)));
    mvc.perform(get("/api/room-blocks?from=2026-11-16&to=2026-11-17").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].roomId").value(195));
    mvc.perform(get("/api/room-blocks").with(user())).andExpect(jsonPath("$", hasSize(5)));
  }

  @Test
  void demoUsersAreCreatedWithoutForcedPasswordChangeAndScopedToReception() {
    assertThat(
            jdbc.queryForList(
                "select username from user_account where username <> 'system' order by username",
                String.class))
        .containsExactly("admin", "auditor", "recepcna.hlavna", "recepcna.pavilony", "veduca");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from user_account where must_change_password", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select r.code from user_role ur join user_account u on u.id = ur.user_id"
                    + " join reception r on r.id = ur.reception_id where u.username = 'recepcna.hlavna'",
                String.class))
        .isEqualTo("MAIN");
  }

  @Test
  void reservationDemoDataIsLoadedWithPartiesAndGuests() throws Exception {
    assertThat(jdbc.queryForObject("select count(*) from client", Integer.class)).isEqualTo(102);
    assertThat(jdbc.queryForObject("select count(*) from reservation_group", Integer.class))
        .isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from reservation", Integer.class))
        .isEqualTo(165);
    assertThat(jdbc.queryForObject("select count(*) from reservation_guest", Integer.class))
        .isEqualTo(195);
    assertThat(jdbc.queryForObject("select count(*) from reservation_party", Integer.class))
        .isEqualTo(15);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from reservation where party_id is not null", Integer.class))
        .isEqualTo(30);

    mvc.perform(get("/api/reservations?q=ukazkova&size=5").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].reservationNumber").exists());
    mvc.perform(get("/api/reservations/1").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reservationNumber").value("R100001"))
        .andExpect(jsonPath("$.guests[0].primaryGuest").value(true));
  }

  @Test
  void demoPricingListIsLoaded() {
    assertThat(jdbc.queryForObject("select count(*) from price_list where status = 'ACTIVE'", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from price_season", Integer.class)).isEqualTo(2);
    // sadzba pre každú kategóriu a sezónu + balíková sadzba
    assertThat(jdbc.queryForObject("select count(*) from price_rate", Integer.class)).isEqualTo(17);
    assertThat(jdbc.queryForObject("select count(*) from package_item", Integer.class)).isEqualTo(2);
  }

  @Test
  void loaderDoesNothingWhenCentreIsNotEmpty() {
    loader.run(args);

    assertThat(jdbc.queryForObject("select count(*) from room", Integer.class)).isEqualTo(431);
    assertThat(jdbc.queryForObject("select count(*) from centre", Integer.class)).isEqualTo(1);
  }
}
