package sk.therasuite.iam;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

/** Skutočný server: príznaky session cookie a CSRF cookie. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class SessionCookieIntegrationTest {

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  @Test
  void sessionCookieIsHttpOnlySecureStrictAndCsrfCookieIsIssued() throws Exception {
    TestUsers.create(jdbc, encoder, "cookie.user", "Heslo-na-test-1", false, "RECEPTIONIST");
    HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> first =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/me")).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(first.statusCode()).isEqualTo(401);
    String xsrf =
        first.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("XSRF-TOKEN="))
            .findFirst()
            .orElseThrow();
    assertThat(xsrf).contains("Secure").contains("SameSite=Strict").doesNotContain("HttpOnly");
    String token = xsrf.substring("XSRF-TOKEN=".length(), xsrf.indexOf(';'));

    HttpResponse<String> login =
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + token)
                .header("X-XSRF-TOKEN", token)
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"cookie.user\",\"password\":\"Heslo-na-test-1\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(login.statusCode()).isEqualTo(200);
    List<String> cookies = login.headers().allValues("Set-Cookie");
    String session =
        cookies.stream().filter(c -> c.startsWith("JSESSIONID=")).findFirst().orElseThrow();
    assertThat(session).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
  }
}
