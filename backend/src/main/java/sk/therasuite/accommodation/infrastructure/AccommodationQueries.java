package sk.therasuite.accommodation.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
  public record StayRef(long id, long roomId, long receptionId, LocalDate dateFrom) {}

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

  public Optional<StayRef> stayRef(long stayId) {
    return jdbc
        .query(
            "select s.id, rm.id, bu.reception_id, s.date_from from stay s"
                + " join bed b on b.id = s.bed_id join room rm on rm.id = b.room_id"
                + " join building bu on bu.id = rm.building_id where s.id = ?",
            (rs, i) ->
                new StayRef(
                    rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, LocalDate.class)),
            stayId)
        .stream()
        .findFirst();
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
