package sk.therasuite.report.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read model zoznamu hostí s miestom ubytovania. */
@Repository
public class GuestLocationQueries {

  /** Jeden pobyt hosťa v daný deň; polia miesta sú {@code null}, ak karta nemá pobyt. */
  public record LocationRow(
      long reservationId,
      String status,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long clientId,
      String firstName,
      String lastName,
      Integer birthYear,
      String building,
      String floor,
      String room,
      Integer bedNumber,
      String bedType,
      LocalDate stayFrom,
      LocalDate stayTo,
      Long doctorId,
      String doctorName) {}

  /**
   * Pobyt patrí do dňa, ak v ňom trvá (date_from &lt;= deň &lt; date_to), alebo ak v ten deň končí
   * a karta má iný pobyt začínajúci v ten deň (zmena izby). Karta bez pobytu sa uvedie, ak v daný
   * deň prichádza.
   */
  private static final String SQL =
      """
      select r.id, r.status, r.arrival_date, r.departure_date,
             pg.id, pg.first_name, pg.last_name, extract(year from pg.birth_date)::int,
             b.name, rm.floor, rm.number, bd.bed_number, bd.type,
             s.date_from, s.date_to, r.attending_doctor_id, u.display_name
      from reservation r
      left join stay s on s.reservation_id = r.id and s.date_from <= ?
           and (s.date_to > ?
                or (s.date_to = ? and exists (select 1 from stay s2
                                              where s2.reservation_id = r.id and s2.date_from = ?)))
      left join bed bd on bd.id = s.bed_id
      left join room rm on rm.id = bd.room_id
      left join building b on b.id = rm.building_id
      left join user_account u on u.id = r.attending_doctor_id
      left join lateral (
        select c.id, c.first_name, c.last_name, c.birth_date
        from reservation_guest g join client c on c.id = g.client_id
        where g.reservation_id = r.id and g.primary_guest
        order by g.id limit 1) pg on true
      where r.status in ('RESERVED', 'CHECKED_IN')
        and (s.id is not null or r.arrival_date = ?)
        and (?::bigint is null or b.centre_id = ?)
        and (?::bigint is null or b.id = ?)
        and (?::bigint is null or r.attending_doctor_id = ?)
      order by pg.last_name nulls last, pg.first_name nulls last, r.id, s.date_from nulls last
      """;

  private final JdbcTemplate jdbc;

  public GuestLocationQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Hlavná recepcia používateľa (user_account.default_reception_id), ak je nastavená. */
  public Long defaultReceptionId(long userId) {
    List<Long> rows =
        jdbc.query(
            "select default_reception_id from user_account where id = ?",
            (rs, i) -> (Long) rs.getObject(1),
            userId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  /** Odtlačok počítadiel zmien všetkých recepcií (zmena údajov = zmena odtlačku). */
  public String changeToken() {
    return String.join(
        ",",
        jdbc.query(
            "select reception_id || ':' || seq from reception_change_seq order by reception_id",
            (rs, i) -> rs.getString(1)));
  }

  public List<LocationRow> find(LocalDate date, Long centreId, Long buildingId, Long doctorId) {
    Date d = Date.valueOf(date);
    return jdbc.query(
        SQL,
        (rs, i) ->
            new LocationRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getDate(3).toLocalDate(),
                rs.getDate(4).toLocalDate(),
                (Long) rs.getObject(5),
                rs.getString(6),
                rs.getString(7),
                (Integer) rs.getObject(8),
                rs.getString(9),
                rs.getString(10),
                rs.getString(11),
                (Integer) rs.getObject(12),
                rs.getString(13),
                rs.getDate(14) == null ? null : rs.getDate(14).toLocalDate(),
                rs.getDate(15) == null ? null : rs.getDate(15).toLocalDate(),
                (Long) rs.getObject(16),
                rs.getString(17)),
        d,
        d,
        d,
        d,
        d,
        centreId,
        centreId,
        buildingId,
        buildingId,
        doctorId,
        doctorId);
  }
}
