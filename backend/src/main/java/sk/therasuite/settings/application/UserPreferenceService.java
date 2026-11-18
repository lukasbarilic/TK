package sk.therasuite.settings.application;

import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.settings.infrastructure.UserPreferenceRepository;
import sk.therasuite.settings.infrastructure.UserPreferenceRepository.Row;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.application.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Nastavenia prihláseného používateľa (HLD kap. 9); každý vidí a mení len svoje. */
@Service
public class UserPreferenceService {

  public static final Set<String> ALLOWED_KEYS =
      Set.of(
          "plan.colors",
          "plan.defaultView",
          "plan.showExtraBeds",
          "plan.showHolidays",
          "reception.default");

  public static final int MAX_VALUE_BYTES = 16 * 1024;

  public record Preference(String key, JsonNode value, long version) {}

  private final UserPreferenceRepository repository;
  private final JsonMapper mapper;

  public UserPreferenceService(UserPreferenceRepository repository, JsonMapper mapper) {
    this.repository = repository;
    this.mapper = mapper;
  }

  @Transactional(readOnly = true)
  public List<Preference> list(long userId) {
    return repository.findAll(userId).stream().map(this::toPreference).toList();
  }

  @Transactional(readOnly = true)
  public Preference get(long userId, String key) {
    return repository
        .find(userId, key)
        .map(this::toPreference)
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Nastavenie neexistuje."));
  }

  /**
   * Uloží hodnotu. {@code expectedVersion} je verzia, ktorú klient videl (pri novom nastavení
   * {@code null} alebo 0); ak je {@code null} pri existujúcom nastavení, zápis prebehne bez
   * kontroly verzie. Nezhoda verzie je 409.
   */
  @AuditedOperation(operation = "USER_PREFERENCE_SET", useCase = "UC16")
  @Transactional
  public Preference put(long userId, String key, JsonNode value, Long expectedVersion) {
    if (!ALLOWED_KEYS.contains(key)) {
      throw unprocessable("UNKNOWN_PREFERENCE_KEY", "Nepovolený kľúč nastavenia: " + key);
    }
    if (value == null || value.isMissingNode()) {
      throw unprocessable("VALIDATION_FAILED", "Chýba hodnota nastavenia.");
    }
    String json;
    try {
      json = mapper.writeValueAsString(value);
    } catch (JacksonException e) {
      throw unprocessable("VALIDATION_FAILED", "Hodnota nie je platný JSON.");
    }
    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
      throw unprocessable("PREFERENCE_TOO_LARGE", "Hodnota nastavenia presahuje 16 kB.");
    }

    Row existing = repository.find(userId, key).orElse(null);
    if (existing == null) {
      if (expectedVersion != null && expectedVersion != 0) {
        throw conflict();
      }
      if (!repository.insert(userId, key, json)) {
        throw conflict();
      }
    } else {
      long expected = expectedVersion != null ? expectedVersion : existing.version();
      if (!repository.update(userId, key, json, expected)) {
        throw conflict();
      }
    }
    return get(userId, key);
  }

  private Preference toPreference(Row row) {
    return new Preference(row.key(), mapper.readTree(row.valueJson()), row.version());
  }

  private static ApiException unprocessable(String code, String message) {
    return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  private static ApiException conflict() {
    return new ApiException(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "Nastavenie medzitým zmenil iný počítač. Načítajte ho znova.");
  }
}
