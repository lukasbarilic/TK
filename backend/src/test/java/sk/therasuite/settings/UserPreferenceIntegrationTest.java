package sk.therasuite.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
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
class UserPreferenceIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  @BeforeEach
  void users() {
    for (String name : new String[] {"pref.a", "pref.b"}) {
      if (!Boolean.TRUE.equals(
          jdbc.queryForObject(
              "select count(*) > 0 from user_account where username = ?", Boolean.class, name))) {
        TestUsers.create(jdbc, encoder, name, "Heslo-na-test-1", false);
      }
    }
    jdbc.update(
        "delete from user_preference where user_id in"
            + " (select id from user_account where username like 'pref.%')");
  }

  private RequestPostProcessor a() {
    return TestUsers.as(jdbc, "pref.a");
  }

  private RequestPostProcessor b() {
    return TestUsers.as(jdbc, "pref.b");
  }

  private org.springframework.test.web.servlet.ResultActions save(
      RequestPostProcessor user, String key, String body, String ifMatch) throws Exception {
    var req =
        put("/api/me/preferences/" + key)
            .with(csrf())
            .with(user)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    if (ifMatch != null) {
      req.header("If-Match", ifMatch);
    }
    return mvc.perform(req);
  }

  @Test
  void savesAndLoadsPreference() throws Exception {
    save(a(), "plan.colors", "{\"value\":{\"STAY\":\"#112233\"}}", null)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(header().string("ETag", "\"1\""));
    save(a(), "plan.showHolidays", "{\"value\":true}", null).andExpect(status().isOk());

    mvc.perform(get("/api/me/preferences").with(a()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)));
    mvc.perform(get("/api/me/preferences/plan.colors").with(a()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.value.STAY").value("#112233"));
    mvc.perform(get("/api/me/preferences/plan.defaultView").with(a()))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from audit_event where operation = 'USER_PREFERENCE_SET'",
                Long.class))
        .isGreaterThanOrEqualTo(2);
  }

  @Test
  void unknownKeyIsRejected() throws Exception {
    save(a(), "plan.unknown", "{\"value\":1}", null).andExpect(status().isUnprocessableEntity());
  }

  @Test
  void tooLargeValueIsRejected() throws Exception {
    String big = "x".repeat(17 * 1024);
    save(a(), "plan.colors", "{\"value\":\"" + big + "\"}", null)
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void versionConflictReturns409() throws Exception {
    save(a(), "reception.default", "{\"value\":1}", null).andExpect(status().isOk());
    save(a(), "reception.default", "{\"value\":2}", "\"1\"")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    save(a(), "reception.default", "{\"value\":3}", "\"1\"").andExpect(status().isConflict());
    save(a(), "reception.default", "{\"value\":3,\"version\":1}", null)
        .andExpect(status().isConflict());
    save(a(), "reception.default", "{\"value\":3,\"version\":2}", null).andExpect(status().isOk());
  }

  @Test
  void userDoesNotSeeOthersPreferences() throws Exception {
    save(a(), "plan.showExtraBeds", "{\"value\":true}", null).andExpect(status().isOk());
    mvc.perform(get("/api/me/preferences").with(b()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
    mvc.perform(get("/api/me/preferences/plan.showExtraBeds").with(b()))
        .andExpect(status().isNotFound());
    save(b(), "plan.showExtraBeds", "{\"value\":false}", null).andExpect(status().isOk());
    mvc.perform(get("/api/me/preferences/plan.showExtraBeds").with(a()))
        .andExpect(jsonPath("$.value").value(true));
  }
}
