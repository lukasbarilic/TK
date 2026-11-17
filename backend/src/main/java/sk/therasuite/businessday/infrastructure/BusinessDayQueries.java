package sk.therasuite.businessday.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sk.therasuite.businessday.domain.BusinessDay;

/** SQL prístup k obchodným dňom, pravidlám uzávierky a podkladom pre pravidlá. */
@Repository
public class BusinessDayQueries {

  /** Nastavenie pravidla pre recepciu; {@code severity} je BLOCKING alebo INFO. */
  public record RuleConfig(boolean enabled, String severity) {}

  /** Karta použitá pri zisteniach (číslo karty, nie osobné údaje). */
  public record CardRef(long id, String number) {}

  private final JdbcTemplate jdbc;

  public BusinessDayQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean receptionExists(long receptionId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from reception where id = ?", Integer.class, receptionId);
    return n != null && n > 0;
  }

  public Optional<BusinessDay> findOpen(long receptionId) {
    return jdbc
        .query(
            "select id, reception_id, business_date, status from business_day"
                + " where reception_id = ? and status = 'OPEN'",
            (rs, i) ->
                new BusinessDay(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getObject(3, LocalDate.class),
                    rs.getString(4)),
            receptionId)
        .stream()
        .findFirst();
  }

  /** Recepcie bez otvoreného aj uzavretého dňa (bez akejkoľvek histórie). */
  public List<Long> receptionsWithoutBusinessDay() {
    return jdbc.queryForList(
        "select r.id from reception r"
            + " where not exists (select 1 from business_day d where d.reception_id = r.id)"
            + " order by r.id",
        Long.class);
  }

  public void insertOpen(long receptionId, LocalDate date) {
    jdbc.update(
        "insert into business_day (reception_id, business_date, status) values (?, ?, 'OPEN')",
        receptionId,
        Date.valueOf(date));
  }

  public void close(long dayId, long userId, String summaryJson) {
    jdbc.update(
        "update business_day set status = 'CLOSED', closed_at = now(), closed_by = ?,"
            + " closing_summary = ?::jsonb where id = ?",
        userId,
        summaryJson,
        dayId);
  }

  public Optional<Long> receptionOfRoom(long roomId) {
    return jdbc
        .queryForList(
            "select b.reception_id from room r join building b on b.id = r.building_id"
                + " where r.id = ?",
            Long.class,
            roomId)
        .stream()
        .findFirst();
  }

  /** Nastavenie pravidla: konkrétna recepcia má prednosť pred všeobecným (reception_id NULL). */
  public Optional<RuleConfig> ruleConfig(String code, long receptionId) {
    return jdbc
        .query(
            "select enabled, severity from closing_rule"
                + " where code = ? and (reception_id = ? or reception_id is null)"
                + " order by reception_id nulls last limit 1",
            (rs, i) -> new RuleConfig(rs.getBoolean(1), rs.getString(2)),
            code,
            receptionId)
        .stream()
        .findFirst();
  }

  /**
   * Karty v danom stave s dátumom príchodu / odchodu najneskôr v daný deň. Karta zatiaľ nie je
   * viazaná na budovu (pobyty prídu v P09), preto sa neobmedzuje na budovy recepcie.
   */
  public List<CardRef> cardsByStatus(String status, String dateColumn, LocalDate day) {
    if (!"arrival_date".equals(dateColumn) && !"departure_date".equals(dateColumn)) {
      throw new IllegalArgumentException(dateColumn);
    }
    return jdbc.query(
        "select id, reservation_number from reservation where status = ? and "
            + dateColumn
            + " <= ? order by id",
        (rs, i) -> new CardRef(rs.getLong(1), rs.getString(2)),
        status,
        Date.valueOf(day));
  }
}
