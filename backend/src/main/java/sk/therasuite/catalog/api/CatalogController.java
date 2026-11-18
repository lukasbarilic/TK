package sk.therasuite.catalog.api;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.catalog.application.CatalogService;
import sk.therasuite.catalog.application.CatalogViews.BuildingView;
import sk.therasuite.catalog.application.CatalogViews.CentreView;
import sk.therasuite.catalog.application.CatalogViews.PayerView;
import sk.therasuite.catalog.application.CatalogViews.ReceptionView;
import sk.therasuite.catalog.application.CatalogViews.RoomCategoryView;
import sk.therasuite.catalog.application.CatalogViews.RoomView;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.shared.api.ConditionalGet;

/** Čítacie endpointy katalógu (vyžadujú oprávnenie PLAN_VIEW). */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

  private final CatalogService service;
  private final PermissionChecker permissions;

  public CatalogController(CatalogService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping("/centres")
  public ResponseEntity<List<CentreView>> centres(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return conditional(ifNoneMatch, service.centres());
  }

  @GetMapping("/receptions")
  public ResponseEntity<List<ReceptionView>> receptions(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return conditional(ifNoneMatch, service.receptions());
  }

  @GetMapping("/buildings")
  public ResponseEntity<List<BuildingView>> buildings(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return conditional(ifNoneMatch, service.buildings());
  }

  @GetMapping("/rooms")
  public ResponseEntity<List<RoomView>> rooms(
      @RequestParam(required = false) Long buildingId,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return conditional(ifNoneMatch, service.rooms(buildingId));
  }

  @GetMapping("/rooms/{id}")
  public ResponseEntity<RoomView> room(
      @PathVariable Long id,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return service
        .room(id)
        .map(room -> conditional(ifNoneMatch, room))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/room-categories")
  public ResponseEntity<List<RoomCategoryView>> roomCategories(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return conditional(ifNoneMatch, service.roomCategories());
  }

  @GetMapping("/payers")
  public ResponseEntity<List<PayerView>> payers(
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    permissions.require(Permissions.PLAN_VIEW);
    return conditional(ifNoneMatch, service.payers());
  }

  /** ETag je odtlačok obsahu (záznamy majú deterministický toString); číselníky sú v cache. */
  private static <T> ResponseEntity<T> conditional(String ifNoneMatch, T value) {
    return ConditionalGet.respond(ifNoneMatch, ConditionalGet.etag(value), () -> value);
  }
}
