package sk.therasuite.settings.infrastructure;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Prístup k tabuľke user_preference; hodnota je JSON ako text. */
@Repository
public class UserPreferenceRepository {

  /** Uložené nastavenie; {@code valueJson} je platný JSON. */
  public record Row(String key, String valueJson, long version) {}

  private final JdbcTemplate jdbc;

  public UserPreferenceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Row> findAll(long userId) {
    return jdbc.query(
        "select key, value::text, version from user_preference where user_id = ? order by key",
        (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3)),
        userId);
  }

  public Optional<Row> find(long userId, String key) {
    return jdbc
        .query(
            "select key, value::text, version from user_preference"
                + " where user_id = ? and key = ?",
            (rs, i) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3)),
            userId,
            key)
        .stream()
        .findFirst();
  }

  /** Založí nastavenie s verziou 1; {@code false}, ak už existuje (súbežný zápis). */
  public boolean insert(long userId, String key, String valueJson) {
    return jdbc.update(
            "insert into user_preference (user_id, key, value) values (?, ?, ?::jsonb)"
                + " on conflict do nothing",
            userId,
            key,
            valueJson)
        == 1;
  }

  /** Zmení hodnotu, ak sa verzia zhoduje; vráti {@code false} pri konflikte. */
  public boolean update(long userId, String key, String valueJson, long expectedVersion) {
    return jdbc.update(
            "update user_preference set value = ?::jsonb, version = version + 1,"
                + " updated_at = now() where user_id = ? and key = ? and version = ?",
            valueJson,
            userId,
            key,
            expectedVersion)
        == 1;
  }
}
