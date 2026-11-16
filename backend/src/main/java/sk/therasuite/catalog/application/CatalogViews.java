package sk.therasuite.catalog.application;

import java.util.List;
import sk.therasuite.catalog.domain.BedType;
import sk.therasuite.catalog.domain.PayerType;

/** Odpovede čítacieho API katalógu. */
public final class CatalogViews {

  private CatalogViews() {}

  public record CentreView(Long id, String code, String name, boolean active) {}

  public record ReceptionView(Long id, String code, String name, String email, boolean active) {}

  public record BuildingView(
      Long id,
      Long centreId,
      Long receptionId,
      String code,
      String name,
      String site,
      int sortOrder,
      boolean active) {}

  public record RoomCategoryView(
      Long id,
      Long centreId,
      Long baseCategoryId,
      String code,
      String name,
      int bedCount,
      boolean active) {}

  public record PayerView(Long id, String code, String name, PayerType type, boolean active) {}

  public record BedView(Long id, int bedNumber, BedType type, boolean active) {}

  public record RoomView(
      Long id,
      Long buildingId,
      String number,
      String floor,
      Long categoryId,
      Long secondaryCategoryId,
      Long departmentId,
      int regularBeds,
      int extraBeds,
      Integer extraBedMaxAge,
      boolean bookable,
      boolean active,
      List<BedView> beds) {}
}
