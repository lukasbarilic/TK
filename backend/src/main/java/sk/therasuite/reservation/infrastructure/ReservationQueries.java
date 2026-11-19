package sk.therasuite.reservation.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sk.therasuite.reservation.domain.ReservationStatus;

/** Vyhľadávanie kariet (bez diakritiky, pg_trgm) a pomocné dotazy cez SQL. */
@Repository
public class ReservationQueries {

  /** Riadok zoznamu: len meno hlavného hosťa, rok narodenia a základné údaje karty. */
  public record Row(
      long id,
      String reservationNumber,
      ReservationStatus status,
      LocalDate arrivalDate,
      LocalDate departureDate,
      long bookedCategoryId,
      String bookedCategoryName,
      String guestFirstName,
      String guestLastName,
      Integer guestBirthYear,
      Long guestClientId) {}

  /** Stránka výsledkov. */
  public record Result(List<Row> rows, long total) {}

  private static final String FROM =
      """
      from reservation r
      join room_category rc on rc.id = r.booked_category_id
      left join lateral (
        select c.id, c.first_name, c.last_name, c.birth_date
        from reservation_guest g join client c on c.id = g.client_id
        where g.reservation_id = r.id and g.primary_guest
        order by g.id limit 1) pg on true
      """;

  private final JdbcTemplate jdbc;

  public ReservationQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Hľadá karty. Každé slovo z {@code q} musí byť obsiahnuté v mene niektorého hosťa alebo v čísle
   * karty; diakritika a veľkosť písmen sa ignorujú. Pri {@code withoutStay} vráti len karty bez
   * akéhokoľvek pobytu (filter sa uplatní pred stránkovaním).
   */
  public Result search(
      String q,
      ReservationStatus status,
      LocalDate from,
      LocalDate to,
      boolean withoutStay,
      int page,
      int size) {
    StringBuilder where = new StringBuilder(" where true");
    List<Object> args = new ArrayList<>();
    if (q != null) {
      for (String token : q.trim().split("\\s+")) {
        if (token.isEmpty()) {
          continue;
        }
        String like = "%" + escapeLike(token) + "%";
        where.append(
            """
             and (f_unaccent(lower(r.reservation_number)) like f_unaccent(lower(?))
               or exists (select 1 from reservation_guest g2 join client c2 on c2.id = g2.client_id
                          where g2.reservation_id = r.id and not g2.cancelled
                            and f_unaccent(lower(c2.last_name || ' ' || c2.first_name))
                                like f_unaccent(lower(?))))
            """);
        args.add(like);
        args.add(like);
      }
    }
    if (status != null) {
      where.append(" and r.status = ?");
      args.add(status.name());
    }
    if (to != null) {
      where.append(" and r.arrival_date < ?");
      args.add(Date.valueOf(to));
    }
    if (from != null) {
      where.append(" and r.departure_date > ?");
      args.add(Date.valueOf(from));
    }
    if (withoutStay) {
      where.append(" and not exists (select 1 from stay s where s.reservation_id = r.id)");
    }
    Long total = jdbc.queryForObject("select count(*) " + FROM + where, Long.class, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    List<Row> rows =
        jdbc.query(
            """
            select r.id, r.reservation_number, r.status, r.arrival_date, r.departure_date,
                   r.booked_category_id, rc.name, pg.first_name, pg.last_name,
                   extract(year from pg.birth_date)::int, pg.id
            """
                + FROM
                + where
                + " order by r.arrival_date desc, r.id desc limit ? offset ?",
            (rs, i) ->
                new Row(
                    rs.getLong(1),
                    rs.getString(2),
                    ReservationStatus.valueOf(rs.getString(3)),
                    rs.getDate(4).toLocalDate(),
                    rs.getDate(5).toLocalDate(),
                    rs.getLong(6),
                    rs.getString(7),
                    rs.getString(8),
                    rs.getString(9),
                    (Integer) rs.getObject(10),
                    (Long) rs.getObject(11)),
            pageArgs.toArray());
    return new Result(rows, total == null ? 0 : total);
  }

  public String nextReservationNumber() {
    Long n = jdbc.queryForObject("select nextval('reservation_number_seq')", Long.class);
    return "R" + n;
  }

  public boolean categoryExists(long id) {
    return exists("room_category", id);
  }

  public boolean payerExists(long id) {
    return exists("payer", id);
  }

  private boolean exists(String table, long id) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select exists (select 1 from " + table + " where id = ?)", Boolean.class, id));
  }

  private static String escapeLike(String s) {
    return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
