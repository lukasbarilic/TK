package sk.therasuite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ApplicationIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayRanAndExtensionsAreEnabled() {
    Integer applied =
        jdbc.queryForObject(
            "select count(*) from flyway_schema_history where version = '1' and success",
            Integer.class);
    List<String> extensions = jdbc.queryForList("select extname from pg_extension", String.class);

    assertThat(applied).isEqualTo(1);
    assertThat(extensions).contains("btree_gist", "pg_trgm");
  }

  @Test
  void healthIsUp() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @Test
  void infoContainsVersion() throws Exception {
    mvc.perform(get("/actuator/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.build.version").isNotEmpty());
  }

  @Test
  void unknownPathReturnsProblemDetail404() throws Exception {
    mvc.perform(get("/api/neexistuje"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
