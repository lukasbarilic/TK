package sk.therasuite.reservation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.reservation.application.ClientService;
import sk.therasuite.reservation.application.ClientService.ClientItem;
import sk.therasuite.reservation.application.ClientService.ClientView;
import sk.therasuite.reservation.application.ClientService.UpdateCommand;

/** Vyhľadanie klienta pri založení karty a oprava jeho údajov (RESERVATION_EDIT). */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

  /** Celé údaje klienta; prázdne nepovinné polia sa zmažú. */
  public record ClientUpdateRequest(
      @NotNull Long version,
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      LocalDate birthDate,
      @Pattern(regexp = "^$|^[A-Za-z]{2}$") String nationality,
      @Email @Size(max = 200) String email,
      @Size(max = 50) String phone) {}

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

  @PutMapping("/{id}")
  public ClientView update(@PathVariable long id, @Valid @RequestBody ClientUpdateRequest body) {
    permissions.require(Permissions.RESERVATION_EDIT);
    return service.update(
        id,
        new UpdateCommand(
            body.version(),
            body.firstName(),
            body.lastName(),
            body.birthDate(),
            body.nationality(),
            body.email(),
            body.phone()));
  }
}
