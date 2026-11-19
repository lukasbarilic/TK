package sk.therasuite.accommodation.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import sk.therasuite.accommodation.domain.OccupancyModel.Bed;
import sk.therasuite.accommodation.domain.OccupancyModel.ExistingStay;
import sk.therasuite.accommodation.domain.OccupancyModel.Room;
import sk.therasuite.accommodation.domain.OccupancyModel.RoomBlock;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.catalog.domain.BedType;

/** SQL prístup modulu ubytovania: podklady pre OccupancyGuard, zoznam pobytov a voľné izby. */
@Repository
public class AccommodationQueries {

  /** Rezervačná karta tak, ako ju potrebuje ubytovanie (bez osobných údajov). */
  public record CardRow(
      long id,
      String status,
      LocalDate arrivalDate,
      LocalDate departureDate,
      long bookedCategoryId,
      boolean exclusiveRoomUse,
      boolean doNotMove,
      long version) {}

  /** Lôžko s izbou a recepciou, ktorej izba patrí. */
  public record BedRow(long bedId, long roomId, long receptionId) {}

  /** Údaje izby pre kontrolu obsadenosti (bez obchodného dňa, ten dopĺňa služba). */
  public record RoomRow(
      long id,
      long categoryId,
      Long secondaryCategoryId,
      boolean active,
      boolean bookable,
      Integer extraBedMaxAge) {

    public Room toModel(LocalDate businessDay) {
      return new Room(
          id, categoryId, secondaryCategoryId, active, bookable, extraBedMaxAge, businessDay);
    }
  }

  /** Pobyt s kontextom (budova, izba, lôžko, kategória pre cenu). */
  public record StayRow(
      long id,
      long reservationId,
      long version,
      long buildingId,
      String buildingName,
      long roomId,
      String roomNumber,
      long bedId,
      int bedNumber,
      String bedType,
      LocalDate dateFrom,
      LocalDate dateTo,
      long priceCategoryId,
      String priceCategoryName,
      boolean locked) {}

  /** Pobyt a recepcia jeho izby (pre zrušenie pobytu). */
  public record StayRef(
      long id, long roomId, long receptionId, LocalDate dateFrom, LocalDate dateTo) {}

  /** Pobyt s izbou a recepciou pre sťahovanie a zmenu dĺžky. */
  public record StayDetail(
      long id,
      long reservationId,
      long version,
      long bedId,
      long roomId,
      long receptionId,
      LocalDate dateFrom,
      LocalDate dateTo,
      boolean locked) {}

  /** Režim kategórie pre cenu a malá skupina karty. */
  public record CardMeta(String priceMode, Long partyId) {}

  /** Voľné lôžko izby. */
  public record FreeBed(long id, int bedNumber, String type) {}

  /** Izba s voľnými lôžkami v celom intervale. */
  public record AvailableRoom(
      long roomId,
      String roomNumber,
      String floor,
      long buildingId,
      String buildingName,
      long receptionId,
      long categoryId,
      String categoryName,
      Long secondaryCategoryId,
      List<FreeBed> freeBeds) {}

  /** Izba plánu s lôžkami a obchodným dňom recepcie jej budovy. */
  public record PlanRoomRow(
      long roomId,
      long buildingId,
      String buildingCode,
      long receptionId,
      LocalDate businessDate,
      String number,
      String floor,
      long categoryId,
      String categoryCode,
      List<FreeBed> beds) {}

  /** Pobyt pre plán; meno hosťa je len na skrátenie štítku, {@code clientId} ide do auditu. */
  public record PlanStayRow(
      long id,
      long reservationId,
      long bedId,
      long roomId,
      LocalDate dateFrom,
      LocalDate dateTo,
      boolean locked,
      String status,
      LocalDate arrivalDate,
      LocalDate departureDate,
      boolean earlyCheckIn,
      boolean lateCheckOut,
      Long partyId,
      Long clientId,
      String lastName,
      String firstName) {}

  public record PlanBlockRow(
      long id, long roomId, String reason, String note, LocalDate dateFrom, LocalDate dateTo) {}

  /** Podklady plánu načítané štyrmi dotazmi. */
  public record PlanData(
      List<PlanRoomRow> rooms,
      List<PlanStayRow> stays,
      List<PlanBlockRow> blocks,
      Set<Long> exclusiveReservationIds) {}

  private static final String STAY_SELECT =
      """
      select s.id, s.reservation_id, s.version, bu.id, bu.name, rm.id, rm.number, b.id,
             b.bed_number, b.type, s.date_from, s.date_to, s.price_category_id, pc.name, s.locked
      from stay s
      join bed b on b.id = s.bed_id
      join room rm on rm.id = b.room_id
      join building bu on bu.id = rm.building_id
      join room_category pc on pc.id = s.price_category_id
      """;

  private final JdbcTemplate jdbc;

  public AccommodationQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<CardRow> card(long id) {
    return jdbc
        .query(
            "select id, status, arrival_date, departure_date, booked_category_id,"
                + " exclusive_room_use, do_not_move, version from reservation where id = ?",
            (rs, i) ->
                new CardRow(
                    rs.getLong(1),
                    rs.getString(2),
                    rs.getObject(3, LocalDate.class),
                    rs.getObject(4, LocalDate.class),
                    rs.getLong(5),
                    rs.getBoolean(6),
                    rs.getBoolean(7),
                    rs.getLong(8)),
            id)
        .stream()
        .findFirst();
  }

  /** Dátumy narodenia hostí karty (bez zrušených hostí a hostí bez dátumu). */
  public List<LocalDate> guestBirthDates(long reservationId) {
    return jdbc.query(
        "select c.birth_date from reservation_guest g join client c on c.id = g.client_id"
            + " where g.reservation_id = ? and not g.cancelled and c.birth_date is not null",
        (rs, i) -> rs.getObject(1, LocalDate.class),
        reservationId);
  }

  public List<Long> guestClientIds(long reservationId) {
    return jdbc.queryForList(
        "select client_id from reservation_guest where reservation_id = ? order by id",
        Long.class,
        reservationId);
  }

  public Optional<BedRow> bed(long bedId) {
    return jdbc
        .query(
            "select b.id, b.room_id, bu.reception_id from bed b join room r on r.id = b.room_id"
                + " join building bu on bu.id = r.building_id where b.id = ?",
            (rs, i) -> new BedRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            bedId)
        .stream()
        .findFirst();
  }

  public Optional<RoomRow> room(long roomId) {
    return jdbc
        .query(
            "select id, category_id, secondary_category_id, active, bookable, extra_bed_max_age"
                + " from room where id = ?",
            (rs, i) ->
                new RoomRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    (Long) rs.getObject(3),
                    rs.getBoolean(4),
                    rs.getBoolean(5),
                    (Integer) rs.getObject(6)),
            roomId)
        .stream()
        .findFirst();
  }

  public List<Bed> bedsOfRoom(long roomId) {
    return jdbc.query(
        "select id, room_id, type, active from bed where room_id = ? order by bed_number",
        (rs, i) ->
            new Bed(
                rs.getLong(1), rs.getLong(2), BedType.valueOf(rs.getString(3)), rs.getBoolean(4)),
        roomId);
  }

  /** Pobyty na lôžkach izby, ktoré sa prekrývajú s intervalom. */
  public List<ExistingStay> staysInRoom(long roomId, StayPeriod period) {
    return jdbc.query(
        """
        select s.id, s.reservation_id, s.bed_id, s.date_from, s.date_to, s.locked,
               r.exclusive_room_use
        from stay s
        join bed b on b.id = s.bed_id
        join reservation r on r.id = s.reservation_id
        where b.room_id = ? and s.date_from < ? and ? < s.date_to
        order by s.id
        """,
        (rs, i) ->
            new ExistingStay(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                new StayPeriod(rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class)),
                rs.getBoolean(6),
                rs.getBoolean(7)),
        roomId,
        Date.valueOf(period.to()),
        Date.valueOf(period.from()));
  }

  public List<RoomBlock> blocksInRoom(long roomId, StayPeriod period) {
    return jdbc.query(
        "select id, room_id, date_from, date_to from room_block"
            + " where room_id = ? and date_from < ? and ? < date_to",
        (rs, i) ->
            new RoomBlock(
                rs.getLong(1),
                rs.getLong(2),
                new StayPeriod(rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class))),
        roomId,
        Date.valueOf(period.to()),
        Date.valueOf(period.from()));
  }

  /** Zamkne riadok izby do konca transakcie: zápisy pobytov v jednej izbe idú za sebou. */
  public void lockRoom(long roomId) {
    jdbc.queryForList("select id from room where id = ? for update", Long.class, roomId);
  }

  /** Odloží kontrolu prekrytia pobytov na lôžku do {@link #enforceStayOverlap()} (dávka zmien). */
  public void deferStayOverlap() {
    jdbc.execute("SET CONSTRAINTS stay_bed_no_overlap DEFERRED");
  }

  /** Vyžiada okamžitú kontrolu prekrytia; porušenie vyhodí výnimku tu, nie až pri commite. */
  public void enforceStayOverlap() {
    jdbc.execute("SET CONSTRAINTS stay_bed_no_overlap IMMEDIATE");
  }

  public List<StayRow> staysOfReservation(long reservationId) {
    return jdbc.query(
        STAY_SELECT + " where s.reservation_id = ? order by s.date_from, s.id",
        StayRowMapper.INSTANCE,
        reservationId);
  }

  public Optional<StayRow> stay(long stayId) {
    return jdbc.query(STAY_SELECT + " where s.id = ?", StayRowMapper.INSTANCE, stayId).stream()
        .findFirst();
  }

  public Optional<StayDetail> stayDetail(long stayId) {
    return jdbc
        .query(
            "select s.id, s.reservation_id, s.version, s.bed_id, rm.id, bu.reception_id,"
                + " s.date_from, s.date_to, s.locked from stay s"
                + " join bed b on b.id = s.bed_id join room rm on rm.id = b.room_id"
                + " join building bu on bu.id = rm.building_id where s.id = ?",
            (rs, i) ->
                new StayDetail(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getLong(5),
                    rs.getLong(6),
                    rs.getObject(7, LocalDate.class),
                    rs.getObject(8, LocalDate.class),
                    rs.getBoolean(9)),
            stayId)
        .stream()
        .findFirst();
  }

  public Optional<CardMeta> cardMeta(long reservationId) {
    return jdbc
        .query(
            "select price_category_mode, party_id from reservation where id = ?",
            (rs, i) -> new CardMeta(rs.getString(1), (Long) rs.getObject(2)),
            reservationId)
        .stream()
        .findFirst();
  }

  /** Ostatné aktívne karty malej skupiny. */
  public List<Long> otherPartyCards(long reservationId, long partyId) {
    return jdbc.queryForList(
        "select id from reservation where party_id = ? and id <> ?"
            + " and status in ('RESERVED', 'CHECKED_IN')",
        Long.class,
        partyId,
        reservationId);
  }

  public Optional<StayRef> stayRef(long stayId) {
    return jdbc
        .query(
            "select s.id, rm.id, bu.reception_id, s.date_from, s.date_to from stay s"
                + " join bed b on b.id = s.bed_id join room rm on rm.id = b.room_id"
                + " join building bu on bu.id = rm.building_id where s.id = ?",
            (rs, i) ->
                new StayRef(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getObject(4, LocalDate.class),
                    rs.getObject(5, LocalDate.class)),
            stayId)
        .stream()
        .findFirst();
  }

  /** Pobyty karty s izbou a recepciou (podklad pre zmenu stavu karty). */
  public List<StayRef> stayRefsOfReservation(long reservationId) {
    return jdbc.query(
        "select s.id, rm.id, bu.reception_id, s.date_from, s.date_to from stay s"
            + " join bed b on b.id = s.bed_id join room rm on rm.id = b.room_id"
            + " join building bu on bu.id = rm.building_id where s.reservation_id = ?"
            + " order by s.date_from, s.id",
        (rs, i) ->
            new StayRef(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getObject(4, LocalDate.class),
                rs.getObject(5, LocalDate.class)),
        reservationId);
  }

  /** Počet rôznych lôžok, na ktorých má karta pobyt. */
  public int assignedBedCount(long reservationId) {
    Integer n =
        jdbc.queryForObject(
            "select count(distinct bed_id) from stay where reservation_id = ?",
            Integer.class,
            reservationId);
    return n == null ? 0 : n;
  }

  /** Počet hostí karty bez zrušených. */
  public int activeGuestCount(long reservationId) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from reservation_guest where reservation_id = ? and not cancelled",
            Integer.class,
            reservationId);
    return n == null ? 0 : n;
  }

  public List<Long> receptionIds() {
    return jdbc.queryForList("select id from reception order by id", Long.class);
  }

  /**
   * Izby s aspoň jedným voľným lôžkom v celom intervale. Izba s blokáciou v intervale alebo s
   * pobytom karty so samostatným bývaním (držané lôžka) sa nevracia. Pri {@code categoryId} bez
   * {@code allCategories} sa berú izby tejto kategórie (primárnej alebo vedľajšej).
   */
  public List<AvailableRoom> availableRooms(
      StayPeriod period,
      Long categoryId,
      boolean allCategories,
      Long receptionId,
      boolean extraBeds) {
    Date from = Date.valueOf(period.from());
    Date to = Date.valueOf(period.to());
    List<Object> args = new ArrayList<>();
    StringBuilder sql =
        new StringBuilder(
            """
            select r.id, r.number, r.floor, bu.id, bu.name, bu.reception_id, r.category_id,
                   rc.name, r.secondary_category_id, b.id, b.bed_number, b.type
            from room r
            join building bu on bu.id = r.building_id
            join room_category rc on rc.id = r.category_id
            join bed b on b.room_id = r.id
            where r.active and r.bookable and bu.active and b.active
            """);
    if (!extraBeds) {
      sql.append(" and b.type = 'REGULAR'");
    }
    if (receptionId != null) {
      sql.append(" and bu.reception_id = ?");
      args.add(receptionId);
    }
    if (categoryId != null && !allCategories) {
      sql.append(" and (r.category_id = ? or r.secondary_category_id = ?)");
      args.add(categoryId);
      args.add(categoryId);
    }
    sql.append(
        """
         and not exists (select 1 from stay s where s.bed_id = b.id
                         and s.date_from < ? and ? < s.date_to)
         and not exists (select 1 from room_block k where k.room_id = r.id
                         and k.date_from < ? and ? < k.date_to)
         and not exists (select 1 from stay s2
                         join bed b2 on b2.id = s2.bed_id
                         join reservation x on x.id = s2.reservation_id
                         where b2.room_id = r.id and x.exclusive_room_use
                           and s2.date_from < ? and ? < s2.date_to)
         order by bu.sort_order, bu.id, r.number, b.bed_number
        """);
    for (int i = 0; i < 3; i++) {
      args.add(to);
      args.add(from);
    }
    Map<Long, AvailableRoom> rooms = new LinkedHashMap<>();
    jdbc.query(
        sql.toString(),
        rs -> {
          long roomId = rs.getLong(1);
          AvailableRoom room =
              rooms.computeIfAbsent(
                  roomId,
                  id -> {
                    try {
                      return new AvailableRoom(
                          id,
                          rs.getString(2),
                          rs.getString(3),
                          rs.getLong(4),
                          rs.getString(5),
                          rs.getLong(6),
                          rs.getLong(7),
                          rs.getString(8),
                          (Long) rs.getObject(9),
                          new ArrayList<>());
                    } catch (java.sql.SQLException e) {
                      throw new IllegalStateException(e);
                    }
                  });
          room.freeBeds().add(new FreeBed(rs.getLong(10), rs.getInt(11), rs.getString(12)));
        },
        args.toArray());
    return new ArrayList<>(rooms.values());
  }

  /**
   * Plán ubytovania: presne štyri dotazy (izby s lôžkami a obchodným dňom, pobyty, blokácie, karty
   * s výhradným užívaním). Izby recepcií, pre ktoré {@code receptionAllowed} vráti false, sa
   * zahodia ešte pred načítaním pobytov. Prázdne filtre ({@code null}) nič neobmedzujú.
   */
  /**
   * Počítadlo zmien: pre danú recepciu jej {@code seq}, inak zoznam dvojíc recepcia:seq všetkých
   * recepcií (plán bez filtra recepcie pokrýva viac recepcií).
   */
  public String changeSeqToken(Long receptionId) {
    if (receptionId != null) {
      List<Long> seq =
          jdbc.queryForList(
              "select seq from reception_change_seq where reception_id = ?",
              Long.class,
              receptionId);
      return seq.isEmpty() ? "0" : String.valueOf(seq.getFirst());
    }
    return String.join(
        ",",
        jdbc.query(
            "select reception_id || ':' || seq from reception_change_seq order by reception_id",
            (rs, i) -> rs.getString(1)));
  }

  public PlanData loadPlan(
      StayPeriod period,
      List<Long> buildingIds,
      List<Long> roomIds,
      Long receptionId,
      Predicate<Long> receptionAllowed) {
    List<PlanRoomRow> rooms = planRooms(buildingIds, roomIds, receptionId, receptionAllowed);
    if (rooms.isEmpty()) {
      return new PlanData(List.of(), List.of(), List.of(), Set.of());
    }
    Long[] ids = rooms.stream().map(PlanRoomRow::roomId).toArray(Long[]::new);
    Date from = Date.valueOf(period.from());
    Date to = Date.valueOf(period.to());
    List<PlanStayRow> stays =
        jdbc.query(
            con -> {
              var ps =
                  con.prepareStatement(
                      """
                      select s.id, s.reservation_id, s.bed_id, b.room_id, s.date_from, s.date_to,
                             s.locked, r.status, r.arrival_date, r.departure_date,
                             r.early_check_in, r.late_check_out, r.party_id,
                             c.id, c.last_name, c.first_name
                      from stay s
                      join bed b on b.id = s.bed_id
                      join reservation r on r.id = s.reservation_id
                      left join lateral (
                          select cl.id, cl.last_name, cl.first_name
                          from reservation_guest g join client cl on cl.id = g.client_id
                          where g.reservation_id = r.id and not g.cancelled
                          order by g.primary_guest desc, g.id
                          limit 1) c on true
                      where b.room_id = any (?) and s.date_from < ? and ? < s.date_to
                      order by b.room_id, s.bed_id, s.date_from
                      """);
              ps.setArray(1, con.createArrayOf("bigint", ids));
              ps.setDate(2, to);
              ps.setDate(3, from);
              return ps;
            },
            (rs, i) ->
                new PlanStayRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getLong(3),
                    rs.getLong(4),
                    rs.getObject(5, LocalDate.class),
                    rs.getObject(6, LocalDate.class),
                    rs.getBoolean(7),
                    rs.getString(8),
                    rs.getObject(9, LocalDate.class),
                    rs.getObject(10, LocalDate.class),
                    rs.getBoolean(11),
                    rs.getBoolean(12),
                    (Long) rs.getObject(13),
                    (Long) rs.getObject(14),
                    rs.getString(15),
                    rs.getString(16)));
    List<PlanBlockRow> blocks =
        jdbc.query(
            con -> {
              var ps =
                  con.prepareStatement(
                      """
                      select k.id, k.room_id, br.name, k.note, k.date_from, k.date_to
                      from room_block k join block_reason br on br.id = k.reason_id
                      where k.room_id = any (?) and k.date_from < ? and ? < k.date_to
                      order by k.room_id, k.date_from
                      """);
              ps.setArray(1, con.createArrayOf("bigint", ids));
              ps.setDate(2, to);
              ps.setDate(3, from);
              return ps;
            },
            (rs, i) ->
                new PlanBlockRow(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getObject(5, LocalDate.class),
                    rs.getObject(6, LocalDate.class)));
    Set<Long> exclusive = new HashSet<>();
    jdbc.query(
        con -> {
          var ps =
              con.prepareStatement(
                  """
                  select distinct r.id
                  from reservation r
                  join stay s on s.reservation_id = r.id
                  join bed b on b.id = s.bed_id
                  where r.exclusive_room_use and b.room_id = any (?)
                    and s.date_from < ? and ? < s.date_to
                  """);
          ps.setArray(1, con.createArrayOf("bigint", ids));
          ps.setDate(2, to);
          ps.setDate(3, from);
          return ps;
        },
        rs -> {
          exclusive.add(rs.getLong(1));
        });
    return new PlanData(rooms, stays, blocks, exclusive);
  }

  private List<PlanRoomRow> planRooms(
      List<Long> buildingIds, List<Long> roomIds, Long receptionId, Predicate<Long> allowed) {
    StringBuilder sql =
        new StringBuilder(
            """
            select r.id, bu.id, bu.code, bu.reception_id, d.business_date, r.number, r.floor,
                   r.category_id, rc.code, b.id, b.bed_number, b.type
            from room r
            join building bu on bu.id = r.building_id
            join room_category rc on rc.id = r.category_id
            left join business_day d on d.reception_id = bu.reception_id and d.status = 'OPEN'
            left join bed b on b.room_id = r.id and b.active
            where r.active and bu.active
            """);
    List<Object> args = new ArrayList<>();
    if (buildingIds != null && !buildingIds.isEmpty()) {
      sql.append(" and r.building_id = any (?)");
      args.add(buildingIds.toArray(Long[]::new));
    }
    if (roomIds != null && !roomIds.isEmpty()) {
      sql.append(" and r.id = any (?)");
      args.add(roomIds.toArray(Long[]::new));
    }
    if (receptionId != null) {
      sql.append(" and bu.reception_id = ?");
      args.add(receptionId);
    }
    sql.append(" order by bu.sort_order, bu.id, r.number, r.id, b.bed_number");
    Map<Long, PlanRoomRow> rooms = new LinkedHashMap<>();
    Map<Long, Boolean> allowedCache = new HashMap<>();
    jdbc.query(
        con -> {
          var ps = con.prepareStatement(sql.toString());
          for (int i = 0; i < args.size(); i++) {
            Object a = args.get(i);
            if (a instanceof Long[] arr) {
              ps.setArray(i + 1, con.createArrayOf("bigint", arr));
            } else {
              ps.setObject(i + 1, a);
            }
          }
          return ps;
        },
        rs -> {
          long reception = rs.getLong(4);
          if (!allowedCache.computeIfAbsent(reception, allowed::test)) {
            return;
          }
          long roomId = rs.getLong(1);
          PlanRoomRow row = rooms.get(roomId);
          if (row == null) {
            row =
                new PlanRoomRow(
                    roomId,
                    rs.getLong(2),
                    rs.getString(3),
                    reception,
                    rs.getObject(5, LocalDate.class),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getLong(8),
                    rs.getString(9),
                    new ArrayList<>());
            rooms.put(roomId, row);
          }
          long bedId = rs.getLong(10);
          if (!rs.wasNull()) {
            row.beds().add(new FreeBed(bedId, rs.getInt(11), rs.getString(12)));
          }
        });
    return new ArrayList<>(rooms.values());
  }

  private static final class StayRowMapper {
    static final org.springframework.jdbc.core.RowMapper<StayRow> INSTANCE =
        (rs, i) ->
            new StayRow(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getLong(4),
                rs.getString(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getLong(8),
                rs.getInt(9),
                rs.getString(10),
                rs.getObject(11, LocalDate.class),
                rs.getObject(12, LocalDate.class),
                rs.getLong(13),
                rs.getString(14),
                rs.getBoolean(15));
  }
}
