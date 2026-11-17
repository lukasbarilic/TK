package sk.therasuite.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sk.therasuite.audit.infrastructure.AuditJson;
import tools.jackson.databind.json.JsonMapper;

class AuditJsonTest {

  private final AuditJson json = new AuditJson(JsonMapper.builder().build());

  @Test
  void removesPasswordAndHashFieldsRecursively() {
    String out =
        json.toJson(
            Map.of(
                "username", "jana",
                "newPassword", "Tajne-heslo-1",
                "nested", List.of(Map.of("passwordHash", "$argon2id$x", "ok", 1))));

    assertThat(out).contains("jana").contains("\"ok\":1");
    assertThat(out)
        .doesNotContain("Tajne-heslo-1")
        .doesNotContain("argon2")
        .doesNotContain("assword");
  }

  @Test
  void keepsFlagsAndTimestampsAboutPassword() {
    assertThat(AuditJson.sensitive("mustChangePassword", true)).isFalse();
    assertThat(AuditJson.sensitive("passwordChangedAt", Instant.now())).isFalse();
    assertThat(AuditJson.sensitive("passwordHash", "x")).isTrue();
    assertThat(AuditJson.sensitive("password", "x")).isTrue();
  }

  @Test
  void nullStaysNull() {
    assertThat(json.toJson(null)).isNull();
  }
}
