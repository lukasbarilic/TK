package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.StayViews.AvailableRoomView;
import sk.therasuite.accommodation.application.StayViews.FreeBedView;
import sk.therasuite.accommodation.domain.StayPeriod;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries.AvailableRoom;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.shared.application.ApiException;

/** Voľné izby pre kartu (UC1): izby s aspoň jedným voľným lôžkom v celom intervale. */
@Service
public class RoomAvailabilityService {

  private final AccommodationQueries queries;
  private final PermissionChecker permissions;

  public RoomAvailabilityService(AccommodationQueries queries, PermissionChecker permissions) {
    this.queries = queries;
    this.permissions = permissions;
  }

  /**
   * Vracia len izby recepcií, pre ktoré má používateľ oprávnenie STAY_CREATE. Bez {@code
   * allCategories} sa pri zadanej {@code categoryId} vrátia izby tejto kategórie; s ním všetky,
   * pričom {@code matchesCategory} povie, ktoré zodpovedajú požadovanej kategórii.
   */
  @Transactional(readOnly = true)
  public List<AvailableRoomView> available(
      LocalDate from,
      LocalDate to,
      Long categoryId,
      boolean allCategories,
      Long receptionId,
      boolean extraBeds) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Parameter 'from' musí byť pred 'to'.");
    }
    if (receptionId != null) {
      permissions.require(Permissions.STAY_CREATE, receptionId);
    } else {
      permissions.require(Permissions.STAY_CREATE);
    }
    return queries
        .availableRooms(new StayPeriod(from, to), categoryId, allCategories, receptionId, extraBeds)
        .stream()
        .filter(r -> permissions.has(Permissions.STAY_CREATE, r.receptionId()))
        .map(r -> view(r, categoryId))
        .toList();
  }

  private static AvailableRoomView view(AvailableRoom r, Long categoryId) {
    boolean matches =
        categoryId == null
            || r.categoryId() == categoryId
            || (r.secondaryCategoryId() != null && r.secondaryCategoryId().equals(categoryId));
    return new AvailableRoomView(
        r.roomId(),
        r.roomNumber(),
        r.floor(),
        r.buildingId(),
        r.buildingName(),
        r.receptionId(),
        r.categoryId(),
        r.categoryName(),
        matches,
        r.freeBeds().stream().map(b -> new FreeBedView(b.id(), b.bedNumber(), b.type())).toList());
  }
}
