package sk.therasuite.accommodation.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read model príchodov a odchodov recepcie (jeden riadok na kartu). */
@Repository
public class MovementQueries {

  /** Riadok príchodu alebo odchodu; izba a lôžko sú {@code null}, ak karta nie je ubytovaná. */
  public record MovementRow(
      long reservationId,
      String reservationNumber,
      String status,
      int guestCount,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long payerId,
      String payerName,
      Long clientId,
      String firstName,
      String lastName,
      Integer birthYear,
      String roomNumber,
      Integer bedNumber) {}

  /**
   * Budovu karty určuje izba pobytu; neubytovaná karta patrí recepciám budov strediska svojej
   * rezervovanej kategórie, v ktorých je izba tejto kategórie. Príchod: pobyt začínajúci v deň
   * príchodu karty; odchod: pobyt končiaci v daný deň.
   */
  private static final String SELECT =
      """
      select distinct on (r.id) r.id, r.reservation_number, r.status, r.adults + r.children,
             r.arrival_date, r.departure_date, p.id, p.name,
             pg.id, pg.first_name, pg.last_name, extract(year from pg.birth_date)::int,
             m.room_number, m.bed_number
      from (
        select s.reservation_id rid, rm.number room_number, bd.bed_number
        from stay s
        join bed bd on bd.id = s.bed_id
        join room rm on rm.id = bd.room_id
        join building b on b.id = rm.building_id
        join reservation r on r.id = s.reservation_id
        where b.reception_id = ? and %s
        union all
        select r.id, null, null
        from reservation r
        join room_category rc on rc.id = r.booked_category_id
        join building b on b.centre_id = rc.centre_id and b.reception_id = ?
        where %s
          and exists (select 1 from room rm
                      where rm.building_id = b.id and rm.category_id = r.booked_category_id)
          and not exists (select 1 from stay s2 where s2.reservation_id = r.id and %s)
      ) m
      join reservation r on r.id = m.rid
      join payer p on p.id = r.payer_id
      left join lateral (
        select c.id, c.first_name, c.last_name, c.birth_date
        from reservation_guest g join client c on c.id = g.client_id
        where g.reservation_id = r.id and g.primary_guest
        order by g.id limit 1) pg on true
      order by r.id, m.room_number nulls last, m.bed_number nulls last
      """;

  private static final String ARRIVAL_STAY =
      "s.date_from = r.arrival_date and r.arrival_date = ? and r.status in ('RESERVED', 'CHECKED_IN')";
  private static final String ARRIVAL_CARD =
      "r.arrival_date = ? and r.status in ('RESERVED', 'CHECKED_IN')";
  private static final String ARRIVAL_NO_STAY = "s2.date_from = r.arrival_date";
  private static final String DEPARTURE_STAY =
      "s.date_to = ? and r.status in ('CHECKED_IN', 'CHECKED_OUT')";
  private static final String DEPARTURE_CARD =
      "r.departure_date = ? and r.status in ('CHECKED_IN', 'CHECKED_OUT')";
  private static final String DEPARTURE_NO_STAY = "s2.date_to = r.departure_date";

  private final JdbcTemplate jdbc;

  public MovementQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<MovementRow> arrivals(long receptionId, LocalDate date) {
    Date d = Date.valueOf(date);
    return jdbc.query(
        SELECT.formatted(ARRIVAL_STAY, ARRIVAL_CARD, ARRIVAL_NO_STAY),
        (rs, i) -> row(rs),
        receptionId,
        d,
        receptionId,
        d);
  }

  public List<MovementRow> departures(long receptionId, LocalDate date) {
    Date d = Date.valueOf(date);
    return jdbc.query(
        SELECT.formatted(DEPARTURE_STAY, DEPARTURE_CARD, DEPARTURE_NO_STAY),
        (rs, i) -> row(rs),
        receptionId,
        d,
        receptionId,
        d);
  }

  private static MovementRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new MovementRow(
        rs.getLong(1),
        rs.getString(2),
        rs.getString(3),
        rs.getInt(4),
        rs.getDate(5).toLocalDate(),
        rs.getDate(6).toLocalDate(),
        rs.getLong(7),
        rs.getString(8),
        (Long) rs.getObject(9),
        rs.getString(10),
        rs.getString(11),
        (Integer) rs.getObject(12),
        rs.getString(13),
        (Integer) rs.getObject(14));
  }
}
