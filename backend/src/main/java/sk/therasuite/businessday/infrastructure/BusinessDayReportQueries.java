package sk.therasuite.businessday.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** SQL podklady reportu dňa a archív reportov; všetky počty sú agregáty bez osobných údajov. */
@Repository
public class BusinessDayReportQueries {

  /** Osoby (dospelí + deti) z kariet recepcie za deň. */
  public record PersonCounts(
      int arrivalsExpected,
      int arrivalsArrived,
      int arrivalsNoShow,
      int departuresExpected,
      int departuresDeparted,
      int inHouse,
      int arrivedStillInHouse,
      int departedToday) {}

  /** Lôžka budovy v daný deň. */
  public record BuildingBeds(long buildingId, String name, int beds, int occupied, int blocked) {}

  /** Lôžka komplexu v jeden deň výhľadu. */
  public record DayBeds(LocalDate date, int beds, int occupied, int blocked) {}

  /** Karta označená ako neprišiel. */
  public record NoShowCard(long id, long version, String number) {}

  /** Riadok archívu: report je JSON text. */
  public record Archived(LocalDate businessDate, String report, long closedBy, String closedAt) {}

  /**
   * Karta patrí recepcii, ak má pobyt v jej budovách, alebo nemá žiadny pobyt (rovnaké pravidlo ako
   * pri CHECKED_IN_WITHOUT_STAY).
   */
  private static final String SCOPE =
      """
      (not exists (select 1 from stay s where s.reservation_id = r.id)
       or exists (select 1 from stay s
                  join bed b on b.id = s.bed_id
                  join room rm on rm.id = b.room_id
                  join building bu on bu.id = rm.building_id
                  where s.reservation_id = r.id and bu.reception_id = ?))
      """;

  private final JdbcTemplate jdbc;

  public BusinessDayReportQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Karty RESERVED s príchodom najneskôr v daný deň, patriace recepcii. */
  public List<NoShowCard> noShowCandidates(long receptionId, LocalDate day) {
    return jdbc.query(
        "select r.id, r.version, r.reservation_number from reservation r"
            + " where r.status = 'RESERVED' and r.arrival_date <= ? and "
            + SCOPE
            + " order by r.id",
        (rs, i) -> new NoShowCard(rs.getLong(1), rs.getLong(2), rs.getString(3)),
        Date.valueOf(day),
        receptionId);
  }

  /** Počty osôb pre príchody, odchody a ľudí v dome jedným dotazom. */
  public PersonCounts personCounts(long receptionId, LocalDate day) {
    String persons = "coalesce(sum(r.adults + r.children) filter (where ";
    return jdbc.queryForObject(
        "select "
            + persons
            + "r.arrival_date = ? and r.status <> 'CANCELLED'), 0),"
            + persons
            + "r.arrival_date = ? and r.status in ('CHECKED_IN', 'CHECKED_OUT')), 0),"
            + persons
            + "r.arrival_date = ? and r.status in ('RESERVED', 'NO_SHOW')), 0),"
            + persons
            + "r.departure_date = ? and r.status in ('CHECKED_IN', 'CHECKED_OUT')), 0),"
            + persons
            + "r.departure_date = ? and r.status = 'CHECKED_OUT'), 0),"
            + persons
            + "r.status = 'CHECKED_IN'), 0),"
            + persons
            + "r.arrival_date = ? and r.status = 'CHECKED_IN'), 0),"
            + persons
            + "r.departure_date = ? and r.status = 'CHECKED_OUT'), 0)"
            + " from reservation r where r.status <> 'CANCELLED' and "
            + SCOPE,
        (rs, i) ->
            new PersonCounts(
                rs.getInt(1),
                rs.getInt(2),
                rs.getInt(3),
                rs.getInt(4),
                rs.getInt(5),
                rs.getInt(6),
                rs.getInt(7),
                rs.getInt(8)),
        Date.valueOf(day),
        Date.valueOf(day),
        Date.valueOf(day),
        Date.valueOf(day),
        Date.valueOf(day),
        Date.valueOf(day),
        Date.valueOf(day),
        receptionId);
  }

  /**
   * Obsadenosť všetkých budov recepcie v daný deň jedným dotazom (bez N+1). Lôžka bez prístelok,
   * obsadené vrátane prístelok; blokácia sa počíta z bežných lôžok blokovaných izieb.
   */
  public List<BuildingBeds> occupancyByBuilding(long receptionId, LocalDate day) {
    Date d = Date.valueOf(day);
    return jdbc.query(
        """
        select bu.id, bu.name,
               count(b.id) filter (where b.type = 'REGULAR') as beds,
               count(b.id) filter (where s.id is not null) as occupied,
               count(b.id) filter (where b.type = 'REGULAR' and blk.id is not null) as blocked
        from building bu
        left join room rm on rm.building_id = bu.id and rm.active
        left join bed b on b.room_id = rm.id and b.active
        left join room_block blk on blk.room_id = rm.id and blk.date_from <= ? and ? < blk.date_to
        left join (select st.id, st.bed_id from stay st
                   join reservation rv on rv.id = st.reservation_id
                   where rv.status in ('RESERVED', 'CHECKED_IN')
                     and st.date_from <= ? and ? < st.date_to) s on s.bed_id = b.id
        where bu.reception_id = ?
        group by bu.id, bu.name
        order by bu.id
        """,
        (rs, i) ->
            new BuildingBeds(
                rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)),
        d,
        d,
        d,
        d,
        receptionId);
  }

  /** Výhľad komplexu na {@code days} dní od nasledujúceho dňa, jedným dotazom. */
  public List<DayBeds> forecast(long receptionId, LocalDate day, int days) {
    return jdbc.query(
        """
        with my_beds as (
            select b.id, b.room_id, b.type from bed b
            join room rm on rm.id = b.room_id and rm.active
            join building bu on bu.id = rm.building_id
            where bu.reception_id = ? and b.active),
        days as (select g::date as d from generate_series(?::date + 1, ?::date + ?, interval '1 day') g)
        select days.d,
               (select count(*) from my_beds where type = 'REGULAR') as beds,
               (select count(*) from my_beds mb
                 join stay st on st.bed_id = mb.id
                 join reservation rv on rv.id = st.reservation_id
                 where rv.status in ('RESERVED', 'CHECKED_IN')
                   and st.date_from <= days.d and days.d < st.date_to) as occupied,
               (select count(*) from my_beds mb
                 join room_block blk on blk.room_id = mb.room_id
                 where mb.type = 'REGULAR'
                   and blk.date_from <= days.d and days.d < blk.date_to) as blocked
        from days order by days.d
        """,
        (rs, i) ->
            new DayBeds(rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getInt(3), rs.getInt(4)),
        receptionId,
        Date.valueOf(day),
        Date.valueOf(day),
        days);
  }

  public void saveReport(long receptionId, LocalDate day, String reportJson, long userId) {
    jdbc.update(
        "insert into business_day_report (reception_id, business_date, report, closed_by)"
            + " values (?, ?, ?::jsonb, ?)",
        receptionId,
        Date.valueOf(day),
        reportJson,
        userId);
  }

  public List<Archived> reports(long receptionId, LocalDate from, LocalDate to) {
    return jdbc.query(
        "select business_date, report::text, closed_by, closed_at::text from business_day_report"
            + " where reception_id = ? and (?::date is null or business_date >= ?::date)"
            + " and (?::date is null or business_date <= ?::date) order by business_date",
        (rs, i) ->
            new Archived(
                rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3), rs.getString(4)),
        receptionId,
        from == null ? null : Date.valueOf(from),
        from == null ? null : Date.valueOf(from),
        to == null ? null : Date.valueOf(to),
        to == null ? null : Date.valueOf(to));
  }

  public Optional<Archived> report(long receptionId, LocalDate day) {
    return jdbc
        .query(
            "select business_date, report::text, closed_by, closed_at::text"
                + " from business_day_report where reception_id = ? and business_date = ?",
            (rs, i) ->
                new Archived(
                    rs.getObject(1, LocalDate.class),
                    rs.getString(2),
                    rs.getLong(3),
                    rs.getString(4)),
            receptionId,
            Date.valueOf(day))
        .stream()
        .findFirst();
  }
}
