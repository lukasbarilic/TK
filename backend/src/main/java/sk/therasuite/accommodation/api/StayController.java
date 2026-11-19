package sk.therasuite.accommodation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import sk.therasuite.accommodation.application.StayService.CreateWithReservationCommand;
import sk.therasuite.accommodation.application.StayService.ReservationWithStay;
import sk.therasuite.accommodation.application.StayViews.StayView;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.reservation.api.ReservationController;

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

  /** Telo založenia karty s lôžkom: polia založenia karty + {@code bedId}. */
  public record CreateWithStayRequest(
      @NotNull LocalDate arrivalDate,
      @NotNull LocalDate departureDate,
      @NotNull Long bookedCategoryId,
      @NotNull Long payerId,
      @NotNull @Min(1) Integer adults,
      @Min(0) Integer children,
      Boolean exclusiveRoomUse,
      @NotEmpty @Valid List<ReservationController.GuestRequest> guests,
      Long attendingDoctorId,
      Boolean earlyCheckIn,
      Boolean lateCheckOut,
      Boolean doNotMove,
      @Size(max = 2000) String note,
      Boolean noteInPlan,
      @NotNull Long bedId,
      Boolean extraBed,
      List<String> confirm) {

    ReservationController.CreateRequest toCreateRequest() {
      return new ReservationController.CreateRequest(
          arrivalDate,
          departureDate,
          bookedCategoryId,
          payerId,
          adults,
          children,
          exclusiveRoomUse,
          guests,
          attendingDoctorId,
          earlyCheckIn,
          lateCheckOut,
          doNotMove,
          note,
          noteInPlan);
    }
  }

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

  @PostMapping("/reservations/with-stay")
  @ResponseStatus(HttpStatus.CREATED)
  public ReservationWithStay createWithStay(@Valid @RequestBody CreateWithStayRequest body) {
    permissions.require(Permissions.RESERVATION_EDIT);
    permissions.require(Permissions.STAY_CREATE);
    if (body.attendingDoctorId() != null) {
      permissions.require(Permissions.DOCTOR_ASSIGN);
    }
    return service.createWithReservation(
        new CreateWithReservationCommand(
            ReservationController.toCommand(body.toCreateRequest()),
            body.bedId(),
            Boolean.TRUE.equals(body.extraBed()),
            body.confirm()));
  }

  @DeleteMapping("/stays/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable long id) {
    permissions.require(Permissions.STAY_CREATE);
    service.delete(id);
  }
}
