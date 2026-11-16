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

/** Čítacie endpointy katalógu (zabezpečenie pribudne v P03). */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

  private final CatalogService service;

  public CatalogController(CatalogService service) {
    this.service = service;
  }

  @GetMapping("/centres")
  public List<CentreView> centres() {
    return service.centres();
  }

  @GetMapping("/receptions")
  public List<ReceptionView> receptions() {
    return service.receptions();
  }

  @GetMapping("/buildings")
  public List<BuildingView> buildings() {
    return service.buildings();
  }

  @GetMapping("/rooms")
  public List<RoomView> rooms(@RequestParam(required = false) Long buildingId) {
    return service.rooms(buildingId);
  }

  @GetMapping("/rooms/{id}")
  public ResponseEntity<RoomView> room(@PathVariable Long id) {
    return service
        .room(id)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/room-categories")
  public List<RoomCategoryView> roomCategories() {
    return service.roomCategories();
  }

  @GetMapping("/payers")
  public List<PayerView> payers() {
    return service.payers();
  }
}
