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
  void demoDataIsLoadedWith56RoomsAndBeds() throws Exception {
    mvc.perform(get("/api/catalog/rooms").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(56)))
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
    assertThat(rooms).isEqualTo(56);
    assertThat(extraMismatch).isZero();
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
    mvc.perform(get("/api/catalog/buildings").with(user())).andExpect(jsonPath("$", hasSize(5)));
    mvc.perform(get("/api/catalog/rooms?buildingId=1").with(user())).andExpect(status().isOk());
    mvc.perform(get("/api/catalog/room-categories").with(user()))
        .andExpect(jsonPath("$", hasSize(8)));
    mvc.perform(get("/api/catalog/payers").with(user())).andExpect(jsonPath("$", hasSize(4)));
    mvc.perform(get("/api/room-blocks?from=2026-11-16&to=2026-11-17").with(user()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].roomId").value(9));
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
  void loaderDoesNothingWhenCentreIsNotEmpty() {
    loader.run(args);

    assertThat(jdbc.queryForObject("select count(*) from room", Integer.class)).isEqualTo(56);
    assertThat(jdbc.queryForObject("select count(*) from centre", Integer.class)).isEqualTo(1);
  }
}
