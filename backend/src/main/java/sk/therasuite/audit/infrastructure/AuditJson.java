package sk.therasuite.audit.infrastructure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Prevod údajov na JSON pre audit; vynecháva polia s heslami a hashmi hesiel. */
@Component
public class AuditJson {

  private final JsonMapper mapper;

  public AuditJson(JsonMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * Polia s heslami, hashmi a tajomstvami sa do auditu nezapisujú. Pole s "password" v názve sa
   * vynecháva, ak nesie text (príznaky a časové údaje o hesle ostávajú).
   */
  public static boolean sensitive(String name, Object value) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.contains("hash") || n.contains("secret") || n.contains("passwd")) {
      return true;
    }
    return n.contains("password") && !(value instanceof Boolean) && !isTemporal(value);
  }

  private static boolean isTemporal(Object value) {
    return value instanceof java.time.temporal.TemporalAccessor;
  }

  /**
   * JSON text očisteného objektu (mapy a zoznamy sa čistia rekurzívne); {@code null} ostáva null.
   */
  public String toJson(Object value) {
    if (value == null) {
      return null;
    }
    return mapper.writeValueAsString(sanitize(value));
  }

  /** Načíta JSON text z databázy (pre odpovede API). */
  public Object parse(String json) {
    return json == null ? null : mapper.readValue(json, Object.class);
  }

  /** Mapy a zoznamy vo vnútri sa čistia podľa mien polí; ostatné hodnoty sa prevedú cez Jackson. */
  Object sanitize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        String key = String.valueOf(e.getKey());
        if (!sensitive(key, e.getValue())) {
          out.put(key, sanitize(e.getValue()));
        }
      }
      return out;
    }
    if (value instanceof Iterable<?> items) {
      List<Object> out = new ArrayList<>();
      items.forEach(i -> out.add(sanitize(i)));
      return out;
    }
    if (value == null
        || value instanceof CharSequence
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    try {
      Object tree = mapper.convertValue(value, Object.class);
      return tree instanceof Map<?, ?> || tree instanceof Iterable<?> ? sanitize(tree) : tree;
    } catch (RuntimeException e) {
      return "<" + value.getClass().getSimpleName() + ">";
    }
  }
}
