package sk.therasuite;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import sk.therasuite.shared.AuthenticatedUser;

/** Pomocné metódy testov: fiktívni používatelia a prihlásenie bez hashovania hesla. */
public final class TestUsers {

  private TestUsers() {}

  /** Založí aktívneho používateľa a pridelí mu globálne roly (kódy rolí). */
  public static long create(
      JdbcTemplate jdbc,
      PasswordEncoder encoder,
      String username,
      String password,
      boolean mustChange,
      String... roleCodes) {
    Long id =
        jdbc.queryForObject(
            "insert into user_account (username, display_name, password_hash, must_change_password)"
                + " values (?, ?, ?, ?) returning id",
            Long.class,
            username,
            "Test " + username,
            encoder.encode(password),
            mustChange);
    for (String role : roleCodes) {
      jdbc.update(
          "insert into user_role (user_id, role_id) select ?, id from role where code = ?",
          id,
          role);
    }
    return id;
  }

  /** Požiadavka vykonaná ako existujúci používateľ (bez prihlasovania). */
  public static RequestPostProcessor as(JdbcTemplate jdbc, String username) {
    Long id =
        jdbc.queryForObject("select id from user_account where username = ?", Long.class, username);
    return SecurityMockMvcRequestPostProcessors.authentication(
        UsernamePasswordAuthenticationToken.authenticated(
            new AuthenticatedUser(id, username), null, List.of()));
  }
}
