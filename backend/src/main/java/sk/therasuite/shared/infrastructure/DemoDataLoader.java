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
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import sk.therasuite.audit.application.AuditEventSpec;
import sk.therasuite.audit.application.AuditService;

/**
 * Načíta fiktívne demo dáta (katalóg, klienti, rezervačné karty) z CSV (classpath:demo/). Beží len
 * v profile {@code demo} a len ak je tabuľka {@code centre} prázdna. Lôžka generuje z izieb
 * (REGULAR 1..regular_beds, EXTRA nasledujú).
 */
@Component
@Profile("demo")
@Order(10)
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
          new String[] {"room_blocks", "room_block"},
          new String[] {"clients", "client"},
          new String[] {"reservation_groups", "reservation_group"},
          new String[] {"reservations", "reservation"},
          new String[] {"reservation_guests", "reservation_guest"});

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
          Map.entry("payer_id", "bigint"),
          Map.entry("booked_category_id", "bigint"),
          Map.entry("group_id", "bigint"),
          Map.entry("party_id", "bigint"),
          Map.entry("client_id", "bigint"),
          Map.entry("reservation_id", "bigint"),
          Map.entry("adults", "int"),
          Map.entry("children", "int"),
          Map.entry("birth_date", "date"),
          Map.entry("arrival_date", "date"),
          Map.entry("departure_date", "date"),
          Map.entry("exclusive_room_use", "boolean"),
          Map.entry("do_not_move", "boolean"),
          Map.entry("early_check_in", "boolean"),
          Map.entry("late_check_out", "boolean"),
          Map.entry("primary_guest", "boolean"),
          Map.entry("sort_order", "int"),
          Map.entry("bed_count", "int"),
          Map.entry("regular_beds", "int"),
          Map.entry("extra_beds", "int"),
          Map.entry("extra_bed_max_age", "int"),
          Map.entry("bookable", "boolean"),
          Map.entry("date_from", "date"),
          Map.entry("date_to", "date"));

  private final JdbcTemplate jdbc;
  private final AuditService audit;

  public DemoDataLoader(JdbcTemplate jdbc, AuditService audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Override
  public void run(ApplicationArguments args) {
    Long existing = jdbc.queryForObject("select count(*) from centre", Long.class);
    if (existing != null && existing > 0) {
      return;
    }
    audit.run(AuditEventSpec.system("DEMO_DATA_LOAD"), this::load);
  }

  private void load() {
    for (String[] file : FILES) {
      List<String[]> rows = read(file[0]);
      if (file[1].equals("reservation")) {
        insertParties(rows);
      }
      insert(file[1], rows);
    }
    generateBeds();
    insertStays(read("stays"));
    List<String> tables = new ArrayList<>(FILES.stream().map(f -> f[1]).toList());
    tables.add("reservation_party");
    tables.add("stay");
    for (String table : tables) {
      jdbc.execute(
          "select setval(pg_get_serial_sequence('"
              + table
              + "', 'id'), (select max(id) from "
              + table
              + "))");
    }
  }

  /** Malé skupiny (reservation_party) vzniknú z rôznych hodnôt stĺpca party_id kariet. */
  private void insertParties(List<String[]> reservationRows) {
    int column = List.of(reservationRows.getFirst()).indexOf("party_id");
    reservationRows.subList(1, reservationRows.size()).stream()
        .map(row -> row[column])
        .filter(value -> !value.isEmpty())
        .map(Long::valueOf)
        .distinct()
        .forEach(id -> jdbc.update("insert into reservation_party (id) values (?)", id));
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

  /**
   * Pobyty: {@code room_id} + {@code bed_number} + {@code bed_type} sa prevedie na {@code bed_id}.
   */
  private void insertStays(List<String[]> rows) {
    List<String> header = List.of(rows.getFirst());
    for (String[] row : rows.subList(1, rows.size())) {
      int updated =
          jdbc.update(
              """
              insert into stay (id, reservation_id, bed_id, date_from, date_to, price_category_id, locked)
              select ?::bigint, ?::bigint, b.id, ?::date, ?::date, ?::bigint, ?::boolean
              from bed b where b.room_id = ?::bigint and b.bed_number = ?::int and b.type = ?
              """,
              row[header.indexOf("id")],
              row[header.indexOf("reservation_id")],
              row[header.indexOf("date_from")],
              row[header.indexOf("date_to")],
              row[header.indexOf("price_category_id")],
              row[header.indexOf("locked")],
              row[header.indexOf("room_id")],
              row[header.indexOf("bed_number")],
              row[header.indexOf("bed_type")]);
      if (updated != 1) {
        throw new IllegalStateException(
            "stays.csv: lôžko sa nenašlo pre riadok " + String.join(",", row));
      }
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
