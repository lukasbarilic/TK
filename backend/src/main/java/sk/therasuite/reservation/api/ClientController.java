package sk.therasuite.reservation.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.reservation.application.ClientService;
import sk.therasuite.reservation.application.ClientService.ClientItem;

/** Vyhľadanie klienta pri založení karty (RESERVATION_EDIT). */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

  private final ClientService service;
  private final PermissionChecker permissions;

  public ClientController(ClientService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping
  public List<ClientItem> search(
      @RequestParam(required = false) String q, @RequestParam(defaultValue = "10") int limit) {
    permissions.require(Permissions.RESERVATION_EDIT);
    return service.search(q, limit).value();
  }
}
