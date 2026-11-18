package sk.therasuite.iam;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AdminRoleIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private RequestPostProcessor user(String username, String role) {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = ?", Boolean.class, username))) {
      TestUsers.create(jdbc, encoder, username, "Heslo-na-test-1", false, role);
    }
    return TestUsers.as(jdbc, username);
  }

  @Test
  void adminSeesSeedRolesWithPermissionsAndLimits() throws Exception {
    mvc.perform(get("/api/admin/roles").with(user("admin.roles", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$[*].code")
                .value(
                    hasItems(
                        "RECEPTIONIST",
                        "RECEPTION_MANAGER",
                        "DOCTOR",
                        "ADMIN",
                        "AUDITOR",
                        "SYSTEM")))
        .andExpect(jsonPath("$[?(@.code=='ADMIN')].systemRole").value(hasItems(true)))
        .andExpect(
            jsonPath("$[?(@.code=='ADMIN')].permissions[*].code").value(hasItems("USER_ADMIN")))
        .andExpect(
            jsonPath("$[?(@.code=='RECEPTIONIST')].permissions[?(@.code=='STAY_CREATE')].limit")
                .value(hasItems(365)));
  }

  @Test
  void adminSeesAllPermissions() throws Exception {
    mvc.perform(get("/api/admin/permissions").with(user("admin.roles", "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(24))))
        .andExpect(jsonPath("$[?(@.code=='STAY_CREATE')].hasLimit").value(hasItems(true)))
        .andExpect(jsonPath("$[?(@.code=='USER_ADMIN')].module").value(hasItems("iam")));
  }

  @Test
  void withoutUserAdminIsForbidden() throws Exception {
    RequestPostProcessor recep = user("recep.roles", "RECEPTIONIST");
    mvc.perform(get("/api/admin/roles").with(recep)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/permissions").with(recep)).andExpect(status().isForbidden());
  }
}
