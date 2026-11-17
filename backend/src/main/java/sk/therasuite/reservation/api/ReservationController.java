package sk.therasuite.reservation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.reservation.application.ReservationService;
import sk.therasuite.reservation.application.ReservationService.CreateCommand;
import sk.therasuite.reservation.application.ReservationService.GuestInput;
import sk.therasuite.reservation.application.ReservationService.UpdateCommand;
import sk.therasuite.reservation.application.ReservationViews.Detail;
import sk.therasuite.reservation.application.ReservationViews.ListItem;
import sk.therasuite.reservation.application.ReservationViews.PageView;
import sk.therasuite.reservation.domain.ReservationStatus;

/** Rezervačné karty: čítanie vyžaduje RESERVATION_VIEW, zápis RESERVATION_EDIT. */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

  public record GuestRequest(
      Long clientId,
      @Size(max = 100) String firstName,
      @Size(max = 100) String lastName,
      LocalDate birthDate,
      @Size(min = 2, max = 2) String nationality,
      @Email @Size(max = 200) String email,
      @Size(max = 50) String phone) {}

  public record CreateRequest(
      @NotNull LocalDate arrivalDate,
      @NotNull LocalDate departureDate,
      @NotNull Long bookedCategoryId,
      @NotNull Long payerId,
      @NotNull @Min(1) Integer adults,
      @Min(0) Integer children,
      Boolean exclusiveRoomUse,
      @NotEmpty @Valid List<GuestRequest> guests) {}

  /** Neuvedené (null) polia sa nemenia. */
  public record UpdateRequest(
      @NotNull Long version,
      LocalDate arrivalDate,
      LocalDate departureDate,
      Long bookedCategoryId,
      Long payerId,
      Boolean doNotMove,
      Boolean earlyCheckIn,
      Boolean lateCheckOut,
      Boolean exclusiveRoomUse) {}

  private final ReservationService service;
  private final PermissionChecker permissions;

  public ReservationController(ReservationService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  /**
   * Parameter {@code receptionId} vyžaduje oprávnenie pre danú recepciu. Karta zatiaľ nie je s
   * recepciou prepojená (vzniká až s pobytmi v P09), takže výsledky nefiltruje.
   */
  @GetMapping
  public PageView<ListItem> search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) ReservationStatus status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Long receptionId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (receptionId != null) {
      permissions.require(Permissions.RESERVATION_VIEW, receptionId);
    } else {
      permissions.require(Permissions.RESERVATION_VIEW);
    }
    return service.search(q, status, from, to, page, size).value();
  }

  @GetMapping("/{id}")
  public Detail get(@PathVariable long id) {
    permissions.require(Permissions.RESERVATION_VIEW);
    return service.get(id).value();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Detail create(@Valid @RequestBody CreateRequest body) {
    permissions.require(Permissions.RESERVATION_EDIT);
    return service.create(
        new CreateCommand(
            body.arrivalDate(),
            body.departureDate(),
            body.bookedCategoryId(),
            body.payerId(),
            body.adults(),
            body.children() == null ? 0 : body.children(),
            Boolean.TRUE.equals(body.exclusiveRoomUse()),
            body.guests().stream()
                .map(g ->
                    new GuestInput(
                        g.clientId(),
                        g.firstName(),
                        g.lastName(),
                        g.birthDate(),
                        g.nationality(),
                        g.email(),
                        g.phone()))
                .toList()));
  }

  @PatchMapping("/{id}")
  public Detail update(@PathVariable long id, @Valid @RequestBody UpdateRequest body) {
    permissions.require(Permissions.RESERVATION_EDIT);
    return service.update(
        id,
        new UpdateCommand(
            body.version(),
            body.arrivalDate(),
            body.departureDate(),
            body.bookedCategoryId(),
            body.payerId(),
            body.doNotMove(),
            body.earlyCheckIn(),
            body.lateCheckOut(),
            body.exclusiveRoomUse()));
  }
}
