package sk.therasuite.reservation.api;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.OfflineCardSelection;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.reservation.application.ReservationService;
import sk.therasuite.reservation.application.ReservationViews.Detail;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.api.ConditionalGet;
import sk.therasuite.shared.application.ApiException;

/** Hromadný detail kariet pre offline režim (oprávnenie RESERVATION_VIEW). */
@RestController
@RequestMapping("/api/offline/reservations")
public class OfflineReservationController {

  static final int MAX_IDS = 200;

  private final ReservationService reservations;
  private final OfflineCardSelection selection;
  private final PermissionChecker permissions;

  public OfflineReservationController(
      ReservationService reservations,
      OfflineCardSelection selection,
      PermissionChecker permissions) {
    this.reservations = reservations;
    this.selection = selection;
    this.permissions = permissions;
  }

  /** Buď {@code receptionId} s {@code from} a {@code to} (ETag/304), alebo {@code ids}. */
  @GetMapping
  public ResponseEntity<List<Detail>> get(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
      @RequestParam(required = false) Long receptionId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) List<Long> ids) {
    boolean window = receptionId != null || from != null || to != null;
    if (window == (ids != null)) {
      throw badRequest("Uveďte buď receptionId, from a to, alebo ids.");
    }
    if (ids != null) {
      permissions.require(Permissions.RESERVATION_VIEW);
      List<Long> distinct = List.copyOf(new LinkedHashSet<>(ids));
      if (distinct.isEmpty() || distinct.size() > MAX_IDS) {
        throw badRequest("Parameter 'ids' musí obsahovať 1 až " + MAX_IDS + " ID.");
      }
      return ResponseEntity.ok(reservations.getAll(selection.visible(distinct)).value());
    }
    if (receptionId == null || from == null || to == null) {
      throw badRequest("Parametre receptionId, from a to sú povinné spolu.");
    }
    if (!from.isBefore(to)) {
      throw badRequest("Parameter 'from' musí byť pred 'to'.");
    }
    String etag = selection.etag(user.id(), receptionId, from, to);
    return ConditionalGet.respond(
        ifNoneMatch,
        etag,
        () -> reservations.getAll(selection.inWindow(receptionId, from, to)).value());
  }

  private static ApiException badRequest(String message) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
  }
}
