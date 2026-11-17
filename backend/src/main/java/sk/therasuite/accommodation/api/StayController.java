package sk.therasuite.accommodation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.StayService;
import sk.therasuite.accommodation.application.StayService.CreateCommand;
import sk.therasuite.accommodation.application.StayViews.StayView;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;

/** Pobyty karty. Rozsah na recepciu kontroluje služba (podľa izby lôžka). */
@RestController
@RequestMapping("/api")
public class StayController {

  /** Telo ubytovania; {@code from}/{@code to} je interval nocí {@code [from, to)}. */
  public record CreateRequest(
      @NotNull Long bedId,
      @NotNull LocalDate from,
      @NotNull LocalDate to,
      Boolean adjustReservationDates,
      List<String> confirm) {}

  private final StayService service;
  private final PermissionChecker permissions;

  public StayController(StayService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping("/reservations/{id}/stays")
  public List<StayView> list(@PathVariable long id) {
    permissions.require(Permissions.RESERVATION_VIEW);
    return service.listForReservation(id).value();
  }

  @PostMapping("/reservations/{id}/stays")
  @ResponseStatus(HttpStatus.CREATED)
  public StayView create(@PathVariable long id, @Valid @RequestBody CreateRequest body) {
    permissions.require(Permissions.STAY_CREATE);
    return service.create(
        id,
        new CreateCommand(
            body.bedId(),
            body.from(),
            body.to(),
            Boolean.TRUE.equals(body.adjustReservationDates()),
            body.confirm()));
  }

  @DeleteMapping("/stays/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id) {
    permissions.require(Permissions.STAY_CREATE);
    service.delete(id);
  }
}
