package sk.therasuite.shared.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Načíta fiktívne demo dáta katalógu z CSV (classpath:demo/). Beží len v profile {@code demo} a len
 * ak je tabuľka {@code centre} prázdna. Lôžka generuje z izieb (REGULAR 1..regular_beds, EXTRA
 * nasledujú).
 */
@Component
@Profile("demo")
public class DemoDataLoader implements ApplicationRunner {

  /** Poradie zodpovedá cudzím kľúčom; stĺpce sa berú z hlavičky CSV. */
  private static final List<String[]> FILES =
      List.of(
          new String[] {"centres", "centre"},
          new String[] {"receptions", "reception"},
          new String[] {"buildings", "building"},
          new String[] {"base_categories", "base_category"},
          new String[] {"room_categories", "room_category"},
          new String[] {"departments", "department"},
          new String[] {"rooms", "room"},
          new String[] {"payers", "payer"},
          new String[] {"block_reasons", "block_reason"},
          new String[] {"room_blocks", "room_block"});

  /** Stĺpce, ktoré sa konvertujú na dátum / číslo / boolean; ostatné sú text. */
  private static final Map<String, String> TYPES =
      Map.ofEntries(
          Map.entry("id", "bigint"),
          Map.entry("centre_id", "bigint"),
          Map.entry("reception_id", "bigint"),
          Map.entry("base_category_id", "bigint"),
          Map.entry("building_id", "bigint"),
          Map.entry("category_id", "bigint"),
          Map.entry("secondary_category_id", "bigint"),
          Map.entry("department_id", "bigint"),
          Map.entry("room_id", "bigint"),
          Map.entry("reason_id", "bigint"),
          Map.entry("sort_order", "int"),
          Map.entry("bed_count", "int"),
          Map.entry("regular_beds", "int"),
          Map.entry("extra_beds", "int"),
          Map.entry("extra_bed_max_age", "int"),
          Map.entry("bookable", "boolean"),
          Map.entry("date_from", "date"),
          Map.entry("date_to", "date"));

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  public DemoDataLoader(JdbcTemplate jdbc, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.tx = tx;
  }

  @Override
  public void run(ApplicationArguments args) {
    Long existing = jdbc.queryForObject("select count(*) from centre", Long.class);
    if (existing != null && existing > 0) {
      return;
    }
    tx.executeWithoutResult(status -> load());
  }

  private void load() {
    for (String[] file : FILES) {
      insert(file[1], read(file[0]));
    }
    generateBeds();
    for (String[] file : FILES) {
      jdbc.execute(
          "select setval(pg_get_serial_sequence('"
              + file[1]
              + "', 'id'), (select max(id) from "
              + file[1]
              + "))");
    }
  }

  private void insert(String table, List<String[]> rows) {
    String[] header = rows.getFirst();
    String columns = String.join(", ", header);
    StringBuilder placeholders = new StringBuilder();
    for (String column : header) {
      if (!placeholders.isEmpty()) {
        placeholders.append(", ");
      }
      placeholders.append("?::").append(TYPES.getOrDefault(column, "text"));
    }
    String sql = "insert into " + table + " (" + columns + ") values (" + placeholders + ")";
    for (String[] row : rows.subList(1, rows.size())) {
      Object[] values = new Object[header.length];
      for (int i = 0; i < header.length; i++) {
        values[i] = i < row.length && !row[i].isEmpty() ? row[i] : null;
      }
      jdbc.update(sql, values);
    }
  }

  private void generateBeds() {
    jdbc.update(
        """
        insert into bed (room_id, bed_number, type)
        select r.id, n, case when n <= r.regular_beds then 'REGULAR' else 'EXTRA' end
        from room r, generate_series(1, r.regular_beds + r.extra_beds) n
        """);
  }

  private List<String[]> read(String name) {
    ClassPathResource resource = new ClassPathResource("demo/" + name + ".csv");
    List<String[]> rows = new ArrayList<>();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (!line.isBlank()) {
          rows.add(line.split(",", -1));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Nepodarilo sa načítať demo/" + name + ".csv", e);
    }
    return rows;
  }
}
