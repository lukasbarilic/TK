package sk.therasuite.settings.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.settings.application.UserPreferenceService;
import sk.therasuite.settings.application.UserPreferenceService.Preference;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.application.ApiException;
import tools.jackson.databind.JsonNode;

/** Nastavenia prihláseného používateľa; stačí prihlásenie, vždy len vlastné nastavenia. */
@RestController
@RequestMapping("/api/me/preferences")
public class UserPreferenceController {

  /** Telo PUT; {@code version} je alternatíva k hlavičke If-Match. */
  public record PutRequest(JsonNode value, Long version) {}

  private final UserPreferenceService service;

  public UserPreferenceController(UserPreferenceService service) {
    this.service = service;
  }

  @GetMapping
  public List<Preference> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.list(user.id());
  }

  @GetMapping("/{key}")
  public ResponseEntity<Preference> get(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String key) {
    return withEtag(service.get(user.id(), key));
  }

  @PutMapping("/{key}")
  public ResponseEntity<Preference> put(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String key,
      @RequestHeader(value = "If-Match", required = false) String ifMatch,
      @RequestBody PutRequest body) {
    Long fromHeader = parseIfMatch(ifMatch);
    if (fromHeader != null && body.version() != null && !fromHeader.equals(body.version())) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "BAD_REQUEST", "If-Match a pole version sa nezhodujú.");
    }
    Long version = fromHeader != null ? fromHeader : body.version();
    return withEtag(service.put(user.id(), key, body.value(), version));
  }

  private static ResponseEntity<Preference> withEtag(Preference p) {
    return ResponseEntity.ok().eTag("\"" + p.version() + "\"").body(p);
  }

  private static Long parseIfMatch(String header) {
    if (header == null || header.isBlank()) {
      return null;
    }
    String v = header.trim();
    if (v.startsWith("W/")) {
      v = v.substring(2);
    }
    v = v.replace("\"", "");
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Neplatná hlavička If-Match.");
    }
  }
}
