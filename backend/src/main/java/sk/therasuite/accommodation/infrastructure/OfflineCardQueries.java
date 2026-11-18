package sk.therasuite.accommodation.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Výber kariet pre offline režim: príslušnosť karty k recepciám a okno plánu. */
@Repository
public class OfflineCardQueries {

  /**
   * Dvojice (karta, recepcia). Budovu karty určuje izba pobytu; neubytovaná karta patrí recepciám
   * budov strediska svojej kategórie, v ktorých je izba tejto kategórie (rovnako ako príchody).
   */
  private static final String CARD_RECEPTIONS =
      """
      select s.reservation_id rid, b.reception_id recid
      from stay s
      join bed bd on bd.id = s.bed_id
      join room rm on rm.id = bd.room_id
      join building b on b.id = rm.building_id
      union
      select r.id, b.reception_id
      from reservation r
      join room_category rc on rc.id = r.booked_category_id
      join building b on b.centre_id = rc.centre_id
      where exists (select 1 from room rm
                    where rm.building_id = b.id and rm.category_id = r.booked_category_id)
        and not exists (select 1 from stay s2 where s2.reservation_id = r.id)
      """;

  private final JdbcTemplate jdbc;

  public OfflineCardQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** ID kariet recepcie s pobytom prekrývajúcim {@code [from, to)} alebo s príchodom v ňom. */
  public List<Long> cardIdsInWindow(long receptionId, LocalDate from, LocalDate to) {
    Date f = Date.valueOf(from);
    Date t = Date.valueOf(to);
    return jdbc.queryForList(
        "select r.id from reservation r join ("
            + CARD_RECEPTIONS
            + ") m on m.rid = r.id and m.recid = ?"
            + " where (r.arrival_date >= ? and r.arrival_date < ?)"
            + " or exists (select 1 from stay s3"
            + "   join bed bd3 on bd3.id = s3.bed_id join room rm3 on rm3.id = bd3.room_id"
            + "   join building b3 on b3.id = rm3.building_id"
            + "   where s3.reservation_id = r.id and b3.reception_id = ?"
            + "     and s3.date_from < ? and s3.date_to > ?)"
            + " order by r.id",
        Long.class,
        receptionId,
        f,
        t,
        receptionId,
        t,
        f);
  }

  /** Pre existujúce karty z {@code ids} ich recepcie (karta môže patriť viacerým). */
  public Map<Long, Set<Long>> receptionsOfCards(Collection<Long> ids) {
    Map<Long, Set<Long>> result = new LinkedHashMap<>();
    if (ids.isEmpty()) {
      return result;
    }
    String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
    jdbc.query(
        "select rid, recid from ("
            + CARD_RECEPTIONS
            + ") m where rid in ("
            + placeholders
            + ")"
            + " order by rid",
        rs -> {
          result.computeIfAbsent(rs.getLong(1), k -> new LinkedHashSet<>()).add(rs.getLong(2));
        },
        new ArrayList<>(ids).toArray());
    return result;
  }
}
