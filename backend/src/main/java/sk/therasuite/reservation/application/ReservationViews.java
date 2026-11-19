package sk.therasuite.reservation.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import sk.therasuite.reservation.domain.PriceCategoryMode;
import sk.therasuite.reservation.domain.PriceType;
import sk.therasuite.reservation.domain.ReservationStatus;

/** Výstupné tvary modulu rezervácií. */
public final class ReservationViews {

  private ReservationViews() {}

  /** Položka zoznamu: bez kontaktných údajov (tie sú len v detaile). */
  public record ListItem(
      long id,
      String reservationNumber,
      String guestFirstName,
      String guestLastName,
      Integer guestBirthYear,
      LocalDate arrivalDate,
      LocalDate departureDate,
      ReservationStatus status,
      long bookedCategoryId,
      String bookedCategoryName) {}

  public record PageView<T>(List<T> content, int page, int size, long totalElements) {}

  public record GuestView(
      long id,
      long clientId,
      String firstName,
      String lastName,
      LocalDate birthDate,
      String nationality,
      String email,
      String phone,
      boolean primaryGuest,
      boolean cancelled) {}

  public record Detail(
      long id,
      String reservationNumber,
      ReservationStatus status,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      Long groupId,
      Long partyId,
      boolean exclusiveRoomUse,
      PriceCategoryMode priceCategoryMode,
      PriceType priceType,
      boolean doNotMove,
      boolean earlyCheckIn,
      boolean lateCheckOut,
      int adults,
      int children,
      long version,
      Instant checkedInAt,
      Instant checkedOutAt,
      Instant cancelledAt,
      String cancelReason,
      List<GuestView> guests) {}
}
