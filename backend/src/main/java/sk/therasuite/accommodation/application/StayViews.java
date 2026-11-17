package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.util.List;

/** Výstupné tvary modulu ubytovania. */
public final class StayViews {

  private StayViews() {}

  /** Pobyt karty: kde a kedy je ubytovaná; {@code priceCategory*} je kategória pre cenu. */
  public record StayView(
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

  public record FreeBedView(long id, int bedNumber, String type) {}

  /** Izba s voľnými lôžkami; {@code matchesCategory} je pravda, ak sedí na požadovanú kategóriu. */
  public record AvailableRoomView(
      long roomId,
      String roomNumber,
      String floor,
      long buildingId,
      String buildingName,
      long receptionId,
      long categoryId,
      String categoryName,
      boolean matchesCategory,
      List<FreeBedView> freeBeds) {}
}
