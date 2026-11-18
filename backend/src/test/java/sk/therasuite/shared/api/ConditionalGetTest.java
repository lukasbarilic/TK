package sk.therasuite.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConditionalGetTest {

  @Test
  void etagIsStableAndSensitiveToParts() {
    assertThat(ConditionalGet.etag("a", 1)).isEqualTo(ConditionalGet.etag("a", 1));
    assertThat(ConditionalGet.etag("a", 1)).isNotEqualTo(ConditionalGet.etag("a", 2));
    assertThat(ConditionalGet.etag("ab", "c")).isNotEqualTo(ConditionalGet.etag("a", "bc"));
  }

  @Test
  void matchesListWeakAndWildcard() {
    String etag = ConditionalGet.etag("x");
    assertThat(ConditionalGet.matches(null, etag)).isFalse();
    assertThat(ConditionalGet.matches("\"other\"", etag)).isFalse();
    assertThat(ConditionalGet.matches("\"other\", " + etag, etag)).isTrue();
    assertThat(ConditionalGet.matches("W/" + etag, etag)).isTrue();
    assertThat(ConditionalGet.matches("*", etag)).isTrue();
  }

  @Test
  void respondSkipsBodySupplierOn304() {
    String etag = ConditionalGet.etag("x");
    AtomicInteger loads = new AtomicInteger();

    var notModified = ConditionalGet.respond(etag, etag, () -> loads.incrementAndGet());
    assertThat(notModified.getStatusCode().value()).isEqualTo(304);
    assertThat(notModified.getBody()).isNull();
    assertThat(loads).hasValue(0);

    var ok = ConditionalGet.respond(null, etag, () -> loads.incrementAndGet());
    assertThat(ok.getStatusCode().value()).isEqualTo(200);
    assertThat(ok.getHeaders().getETag()).isEqualTo(etag);
    assertThat(loads).hasValue(1);
  }
}
