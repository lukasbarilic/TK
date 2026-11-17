package sk.therasuite.accommodation.domain;

import java.time.LocalDate;
import java.util.List;
import sk.therasuite.catalog.domain.BedType;

/** Doménové záznamy, ktoré dostáva {@link OccupancyGuard}; služby nič nenačítavajú samy. */
public final class OccupancyModel {

  private OccupancyModel() {}

  /**
   * Izba. {@code businessDay} je aktuálny obchodný deň recepcie izby (HLD kap. 5.8), {@code
   * extraBedMaxAge} je najvyšší vek hosťa na prístelke ({@code null} = bez obmedzenia).
   */
  public record Room(
      long id,
      long categoryId,
      Long secondaryCategoryId,
      boolean active,
      boolean bookable,
      Integer extraBedMaxAge,
      LocalDate businessDay) {}

  public record Bed(long id, long roomId, BedType type, boolean active) {}

  /** Uložený pobyt; {@code exclusiveRoomUse} preberá z jeho karty. */
  public record ExistingStay(
      long id,
      long reservationId,
      long bedId,
      StayPeriod period,
      boolean locked,
      boolean exclusiveRoomUse) {}

  public record RoomBlock(long id, long roomId, StayPeriod period) {}

  /** Rezervačná karta; {@code guestAges} sú vekové hodnoty hostí (prázdne = neoverujú sa). */
  public record Reservation(
      long id,
      long bookedCategoryId,
      LocalDate arrivalDate,
      LocalDate departureDate,
      boolean exclusiveRoomUse,
      boolean doNotMove,
      List<Integer> guestAges) {}

  /** Oprávnenia používateľa; {@code maxStayNights} je limit z {@code STAY_CREATE}. */
  public record UserRights(
      boolean backdate, boolean moveLocked, boolean otherCategory, int maxStayNights) {}

  /** Údaje načítané volajúcim: izby, lôžka, pobyty a blokácie v dotknutom intervale, karty. */
  public record Context(
      List<Room> rooms,
      List<Bed> beds,
      List<ExistingStay> stays,
      List<RoomBlock> blocks,
      List<Reservation> reservations) {}

  /**
   * Navrhovaný stav jedného pobytu. {@code stayId == null} je nový pobyt. {@code effectiveFrom} je
   * začiatok zmeny (deň sťahovania, resp. deň rozdelenia); používa sa pri pravidlách „nie spätne“ a
   * „v rámci karty“.
   */
  public record StayChange(
      Long stayId, long reservationId, long bedId, StayPeriod period, LocalDate effectiveFrom) {

    public static StayChange create(long reservationId, long bedId, StayPeriod period) {
      return new StayChange(null, reservationId, bedId, period, period.from());
    }

    public static StayChange update(
        long stayId, long reservationId, long bedId, StayPeriod period, LocalDate effectiveFrom) {
      return new StayChange(stayId, reservationId, bedId, period, effectiveFrom);
    }
  }
}
