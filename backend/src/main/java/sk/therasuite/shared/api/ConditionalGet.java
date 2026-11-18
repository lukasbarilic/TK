package sk.therasuite.shared.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Spoločný mechanizmus podmieneného čítania (ETag a 304 Not Modified). Použitie v controlleri:
 * {@code ConditionalGet.respond(ifNoneMatch, ConditionalGet.etag(parts...), () -> data)}. Ak sa
 * hlavička If-None-Match zhoduje, telo sa nenačíta (dodávateľ sa nezavolá), takže nevznikne ani
 * audit čítania.
 */
public final class ConditionalGet {

  private ConditionalGet() {}

  /** Silný ETag (v úvodzovkách) ako odtlačok SHA-256 zo zadaných častí. */
  public static String etag(Object... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object part : parts) {
        digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
      }
      byte[] hash = digest.digest();
      return "\""
          + Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 22)
          + "\"";
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Zhoda hlavičky If-None-Match (zoznam, {@code *}, slabé validátory) s aktuálnym ETagom. */
  public static boolean matches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
      return false;
    }
    if (ifNoneMatch.trim().equals("*")) {
      return true;
    }
    for (String candidate : ifNoneMatch.split(",")) {
      String c = candidate.trim();
      if (c.startsWith("W/")) {
        c = c.substring(2);
      }
      if (c.equals(etag)) {
        return true;
      }
    }
    return false;
  }

  /** 304 bez tela, ak sa ETag zhoduje; inak 200 s telom z dodávateľa a hlavičkou ETag. */
  public static <T> ResponseEntity<T> respond(String ifNoneMatch, String etag, Supplier<T> body) {
    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(304).eTag(etag).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
        .body(body.get());
  }
}
