package sk.therasuite.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AuthIntegrationTest {

  private static final String PASSWORD = "Heslo-na-test-1";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private MvcResult login(String username, String password) throws Exception {
    return mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
        .andReturn();
  }

  @Test
  void loginCreatesSessionAndMeReturnsRolesAndPermissions() throws Exception {
    TestUsers.create(jdbc, encoder, "login.ok", PASSWORD, false, "RECEPTIONIST");

    MvcResult result = login("login.ok", PASSWORD);
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
    assertThat(session).isNotNull();

    mvc.perform(get("/api/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("login.ok"))
        .andExpect(jsonPath("$.roles[0].code").value("RECEPTIONIST"))
        .andExpect(jsonPath("$.permissions[?(@.code == 'STAY_CREATE')].limit").value(365))
        .andExpect(jsonPath("$.permissions[*].code", hasItem("PLAN_VIEW")));
    mvc.perform(get("/api/catalog/centres").session(session)).andExpect(status().isOk());

    mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void wrongPasswordAndUnknownUserReturn401() throws Exception {
    TestUsers.create(jdbc, encoder, "login.bad", PASSWORD, false, "RECEPTIONIST");

    assertThat(login("login.bad", "zle-heslo-123").getResponse().getStatus()).isEqualTo(401);
    assertThat(login("neexistuje", PASSWORD).getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void systemUserCannotLogIn() throws Exception {
    assertThat(login("system", "system").getResponse().getStatus()).isEqualTo(401);
  }

  @Test
  void accountIsLockedAfterMaxFailedAttemptsAndAdminCanUnlock() throws Exception {
    long id = TestUsers.create(jdbc, encoder, "login.lock", PASSWORD, false, "RECEPTIONIST");
    TestUsers.create(jdbc, encoder, "lock.admin", PASSWORD, false, "ADMIN");

    for (int i = 0; i < 5; i++) {
      assertThat(login("login.lock", "zle-heslo-123").getResponse().getStatus()).isEqualTo(401);
    }
    assertThat(
            jdbc.queryForObject("select status from user_account where id = ?", String.class, id))
        .isEqualTo("LOCKED");
    MvcResult locked = login("login.lock", PASSWORD);
    assertThat(locked.getResponse().getStatus()).isEqualTo(423);

    mvc.perform(
            post("/api/admin/users/" + id + "/unlock")
                .with(csrf())
                .with(TestUsers.as(jdbc, "lock.admin")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    assertThat(login("login.lock", PASSWORD).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void lockExpiresAfterLockDuration() throws Exception {
    long id = TestUsers.create(jdbc, encoder, "login.expire", PASSWORD, false, "RECEPTIONIST");
    jdbc.update(
        "update user_account set status = 'LOCKED', locked_until = now() - interval '1 minute'"
            + " where id = ?",
        id);

    assertThat(login("login.expire", PASSWORD).getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void mustChangePasswordAllowsOnlyPasswordChangeAndLogout() throws Exception {
    TestUsers.create(jdbc, encoder, "login.must", PASSWORD, true, "RECEPTIONIST");
    MvcResult result = login("login.must", PASSWORD);
    MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);

    mvc.perform(get("/api/me").session(session))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));
    mvc.perform(get("/api/catalog/centres").session(session))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"));

    mvc.perform(
            post("/api/me/password")
                .with(csrf())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"kratke\"}".formatted(PASSWORD)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/me/password")
                .with(csrf())
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"currentPassword\":\"%s\",\"newPassword\":\"Nove-heslo-123\"}"
                        .formatted(PASSWORD)))
        .andExpect(status().isNoContent());

    mvc.perform(get("/api/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mustChangePassword").value(false));
    assertThat(login("login.must", "Nove-heslo-123").getResponse().getStatus()).isEqualTo(200);
  }

  @Test
  void logoutIsAllowedWhilePasswordChangeIsRequired() throws Exception {
    TestUsers.create(jdbc, encoder, "login.must2", PASSWORD, true);
    MockHttpSession session =
        (MockHttpSession) login("login.must2", PASSWORD).getRequest().getSession(false);

    mvc.perform(post("/api/auth/logout").with(csrf()).session(session))
        .andExpect(status().isNoContent());
  }

  @Test
  void anonymousGets401ButHealthIsPublic() throws Exception {
    mvc.perform(get("/api/catalog/centres"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void postWithoutCsrfTokenIsRejected() throws Exception {
    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"a\",\"password\":\"b\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void authenticatedUserWithoutPermissionGets403() throws Exception {
    TestUsers.create(jdbc, encoder, "login.audit", PASSWORD, false, "AUDITOR");
    var auditor = TestUsers.as(jdbc, "login.audit");

    mvc.perform(get("/api/catalog/centres").with(auditor))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get("/api/room-blocks").with(auditor)).andExpect(status().isForbidden());
    mvc.perform(get("/api/admin/users").with(auditor)).andExpect(status().isForbidden());
  }

  @Test
  void disabledUserLosesExistingSession() throws Exception {
    long id = TestUsers.create(jdbc, encoder, "login.disabled", PASSWORD, false, "RECEPTIONIST");
    MockHttpSession session =
        (MockHttpSession) login("login.disabled", PASSWORD).getRequest().getSession(false);
    jdbc.update("update user_account set status = 'DISABLED' where id = ?", id);

    mvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    assertThat(login("login.disabled", PASSWORD).getResponse().getStatus()).isEqualTo(401);
  }
}
