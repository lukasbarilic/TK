package sk.therasuite.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AdminUserIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private RequestPostProcessor admin() {
    if (!Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select count(*) > 0 from user_account where username = 'admin.test'",
            Boolean.class))) {
      TestUsers.create(jdbc, encoder, "admin.test", "Heslo-na-test-1", false, "ADMIN");
    }
    return TestUsers.as(jdbc, "admin.test");
  }

  @Test
  void adminCreatesUserWithOneTimePasswordThatMustBeChanged() throws Exception {
    String body =
        mvc.perform(
                post("/api/admin/users")
                    .with(csrf())
                    .with(admin())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"username":"nova.recepcna","displayName":"Nová Recepčná",
                         "email":"nova@example.invalid",
                         "roles":[{"roleCode":"RECEPTIONIST"}]}
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user.status").value("ACTIVE"))
            .andExpect(jsonPath("$.user.mustChangePassword").value(true))
            .andExpect(jsonPath("$.user.roles", hasSize(1)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String password = JsonPath.read(body, "$.oneTimePassword");
    Long id = JsonPath.parse(body).read("$.user.id", Long.class);

    assertThat(password).hasSize(16);
    assertThat(
            jdbc.queryForObject(
                "select password_hash from user_account where id = ?", String.class, id))
        .startsWith("$argon2id$");
    assertThat(
            jdbc.queryForObject("select created_by from user_account where id = ?", Long.class, id))
        .isNotNull();
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"username\":\"nova.recepcna\",\"password\":\"%s\"}".formatted(password)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(true));
  }

  @Test
  void duplicateUsernameIsConflict() throws Exception {
    String json = "{\"username\":\"dup.user\",\"displayName\":\"Dup\"}";
    mvc.perform(
            post("/api/admin/users")
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/admin/users")
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andExpect(status().isConflict());
  }

  @Test
  void nonAdminCannotManageUsers() throws Exception {
    TestUsers.create(jdbc, encoder, "admin.none", "Heslo-na-test-1", false, "RECEPTIONIST");
    RequestPostProcessor recep = TestUsers.as(jdbc, "admin.none");

    mvc.perform(get("/api/admin/users").with(recep)).andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/users")
                .with(csrf())
                .with(recep)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x.y\",\"displayName\":\"X\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void rolesCanBeReplacedPasswordResetAndUserDisabled() throws Exception {
    long id = TestUsers.create(jdbc, encoder, "admin.target", "Heslo-na-test-1", false, "DOCTOR");
    Long receptionId =
        jdbc.queryForObject(
            "insert into reception (code, name) values ('ADM-R', 'R') returning id", Long.class);

    mvc.perform(
            put("/api/admin/users/" + id + "/roles")
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"roles\":[{\"roleCode\":\"RECEPTIONIST\",\"receptionId\":%d}]}"
                        .formatted(receptionId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles", hasSize(1)))
        .andExpect(jsonPath("$.roles[0].roleCode").value("RECEPTIONIST"));
    mvc.perform(
            put("/api/admin/users/" + id + "/roles")
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[{\"roleCode\":\"SYSTEM\"}]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/admin/users/" + id + "/roles")
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\":[{\"roleCode\":\"NEEXISTUJE\"}]}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/admin/users/" + id + "/reset-password").with(csrf()).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oneTimePassword").isNotEmpty());
    assertThat(
            jdbc.queryForObject(
                "select must_change_password from user_account where id = ?", Boolean.class, id))
        .isTrue();

    mvc.perform(
            patch("/api/admin/users/" + id)
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\",\"displayName\":\"Nové meno\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("DISABLED"))
        .andExpect(jsonPath("$.displayName").value("Nové meno"));
    mvc.perform(
            patch("/api/admin/users/" + id)
                .with(csrf())
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"LOCKED\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void systemUserCannotBeModified() throws Exception {
    Long systemId =
        jdbc.queryForObject("select id from user_account where username = 'system'", Long.class);

    mvc.perform(post("/api/admin/users/" + systemId + "/reset-password").with(csrf()).with(admin()))
        .andExpect(status().isBadRequest());
  }
}
