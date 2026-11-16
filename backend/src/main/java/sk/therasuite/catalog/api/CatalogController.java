package sk.therasuite.catalog.api;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
  public List<CentreView> centres() {
    permissions.require(Permissions.PLAN_VIEW);
    return service.centres();
  }

  @GetMapping("/receptions")
  public List<ReceptionView> receptions() {
    permissions.require(Permissions.PLAN_VIEW);
    return service.receptions();
  }

  @GetMapping("/buildings")
  public List<BuildingView> buildings() {
    permissions.require(Permissions.PLAN_VIEW);
    return service.buildings();
  }

  @GetMapping("/rooms")
  public List<RoomView> rooms(@RequestParam(required = false) Long buildingId) {
    permissions.require(Permissions.PLAN_VIEW);
    return service.rooms(buildingId);
  }

  @GetMapping("/rooms/{id}")
  public ResponseEntity<RoomView> room(@PathVariable Long id) {
    permissions.require(Permissions.PLAN_VIEW);
    return service
        .room(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/room-categories")
  public List<RoomCategoryView> roomCategories() {
    permissions.require(Permissions.PLAN_VIEW);
    return service.roomCategories();
  }

  @GetMapping("/payers")
  public List<PayerView> payers() {
    permissions.require(Permissions.PLAN_VIEW);
    return service.payers();
  }
}
