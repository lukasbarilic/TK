package sk.therasuite.settings;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ClientConfigIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  @Test
  void returnsValuesFromConfigurationForAnyLoggedInUser() throws Exception {
    // bez roly, teda bez akéhokoľvek oprávnenia
    TestUsers.create(jdbc, encoder, "cfg.bez.roly", "Heslo-na-test-1", false);

    mvc.perform(get("/api/client-config").with(TestUsers.as(jdbc, "cfg.bez.roly")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.apiVersion").value(1))
        .andExpect(jsonPath("$.minClientVersion").value("0.1.0"))
        .andExpect(jsonPath("$.serverTime").isNotEmpty())
        .andExpect(jsonPath("$.idleTimeoutSec").value(1800))
        .andExpect(jsonPath("$.offline.planDaysBack").value(7))
        .andExpect(jsonPath("$.offline.planDaysAhead").value(60))
        .andExpect(jsonPath("$.offline.syncIntervalSec").value(300))
        .andExpect(jsonPath("$.offline.maxAgeDays").value(nullValue()));
  }

  @Test
  void rejectsAnonymous() throws Exception {
    mvc.perform(get("/api/client-config")).andExpect(status().is4xxClientError());
  }
}
