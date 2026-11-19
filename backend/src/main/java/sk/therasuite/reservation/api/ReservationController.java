package sk.therasuite.reservation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import sk.therasuite.reservation.application.ReservationCommentService;
import sk.therasuite.reservation.application.ReservationService;
import sk.therasuite.reservation.application.ReservationService.CreateCommand;
import sk.therasuite.reservation.application.ReservationService.GuestInput;
import sk.therasuite.reservation.application.ReservationService.UpdateCommand;
import sk.therasuite.reservation.application.ReservationStatusService;
import sk.therasuite.reservation.application.ReservationViews.CommentView;
import sk.therasuite.reservation.application.ReservationViews.Detail;
import sk.therasuite.reservation.application.ReservationViews.ListItem;
import sk.therasuite.reservation.application.ReservationViews.PageView;
import sk.therasuite.reservation.domain.ReservationStatus;

/**
 * Rezervačné karty: čítanie vyžaduje RESERVATION_VIEW, zápis RESERVATION_EDIT, zmeny stavu
 * RESERVATION_STATUS (opravy RESERVATION_STATUS_FIX).
 */
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
      @NotEmpty @Valid List<GuestRequest> guests,
      Long attendingDoctorId,
      Boolean earlyCheckIn,
      Boolean lateCheckOut,
      Boolean doNotMove,
      @Size(max = 2000) String note,
      Boolean noteInPlan) {}

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
      Boolean exclusiveRoomUse,
      @Size(max = 2000) String note,
      Boolean noteInPlan) {}

  public record CommentRequest(@NotBlank @Size(max = 2000) String text) {}

  /** Telo operácie so stavom karty; {@code version} je verzia, z ktorej klient vychádzal. */
  public record StatusRequest(@NotNull Long version) {}

  public record CheckOutRequest(@NotNull Long version, List<String> confirm) {}

  public record CancelRequest(@NotNull Long version, @NotBlank @Size(max = 500) String reason) {}

  private final ReservationService service;
  private final ReservationStatusService statuses;
  private final ReservationCommentService comments;
  private final PermissionChecker permissions;

  public ReservationController(
      ReservationService service,
      ReservationStatusService statuses,
      ReservationCommentService comments,
      PermissionChecker permissions) {
    this.service = service;
    this.statuses = statuses;
    this.comments = comments;
    this.permissions = permissions;
  }

  /**
   * Parameter {@code receptionId} vyžaduje oprávnenie pre danú recepciu. Karta zatiaľ nie je s
   * recepciou prepojená (vzniká až s pobytmi v P09), takže výsledky nefiltruje. Parameter {@code
   * withoutStay=true} vráti len karty bez pobytu (UC2 – ubytovanie z plánu).
   */
  @GetMapping
  public PageView<ListItem> search(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) ReservationStatus status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Long receptionId,
      @RequestParam(defaultValue = "false") boolean withoutStay,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    if (receptionId != null) {
      permissions.require(Permissions.RESERVATION_VIEW, receptionId);
    } else {
      permissions.require(Permissions.RESERVATION_VIEW);
    }
    return service.search(q, status, from, to, withoutStay, page, size).value();
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
    if (body.attendingDoctorId() != null) {
      permissions.require(Permissions.DOCTOR_ASSIGN);
    }
    return service.create(toCommand(body));
  }

  /** Príkaz na založenie karty z tela požiadavky (použije ho aj {@code with-stay}). */
  public static CreateCommand toCommand(CreateRequest body) {
    return new CreateCommand(
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
            .toList(),
        body.attendingDoctorId(),
        Boolean.TRUE.equals(body.earlyCheckIn()),
        Boolean.TRUE.equals(body.lateCheckOut()),
        Boolean.TRUE.equals(body.doNotMove()),
        body.note(),
        Boolean.TRUE.equals(body.noteInPlan()));
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
            body.exclusiveRoomUse(),
            body.note(),
            body.noteInPlan()));
  }

  @GetMapping("/{id}/comments")
  public List<CommentView> comments(@PathVariable long id) {
    permissions.require(Permissions.RESERVATION_VIEW);
    return comments.list(id).value();
  }

  @PostMapping("/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public CommentView addComment(@PathVariable long id, @Valid @RequestBody CommentRequest body) {
    permissions.require(Permissions.RESERVATION_EDIT);
    return comments.add(id, body.text());
  }

  @PostMapping("/{id}/comments/{commentId}/hide")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void hideComment(@PathVariable long id, @PathVariable long commentId) {
    permissions.require(Permissions.RESERVATION_EDIT);
    comments.hide(id, commentId);
  }

  @PostMapping("/{id}/check-in")
  public Detail checkIn(@PathVariable long id, @Valid @RequestBody StatusRequest body) {
    permissions.require(Permissions.RESERVATION_STATUS);
    return statuses.checkIn(id, body.version());
  }

  @PostMapping("/{id}/check-out")
  public Detail checkOut(@PathVariable long id, @Valid @RequestBody CheckOutRequest body) {
    permissions.require(Permissions.RESERVATION_STATUS);
    return statuses.checkOut(id, body.version(), body.confirm());
  }

  @PostMapping("/{id}/cancel")
  public Detail cancel(@PathVariable long id, @Valid @RequestBody CancelRequest body) {
    permissions.require(Permissions.RESERVATION_STATUS);
    return statuses.cancel(id, body.version(), body.reason());
  }

  @PostMapping("/{id}/no-show")
  public Detail noShow(@PathVariable long id, @Valid @RequestBody StatusRequest body) {
    permissions.require(Permissions.RESERVATION_STATUS);
    return statuses.noShow(id, body.version());
  }

  @PostMapping("/{id}/undo-check-in")
  public Detail undoCheckIn(@PathVariable long id, @Valid @RequestBody StatusRequest body) {
    permissions.require(Permissions.RESERVATION_STATUS_FIX);
    return statuses.undoCheckIn(id, body.version());
  }

  @PostMapping("/{id}/revert-no-show")
  public Detail revertNoShow(@PathVariable long id, @Valid @RequestBody StatusRequest body) {
    permissions.require(Permissions.RESERVATION_STATUS_FIX);
    return statuses.revertNoShow(id, body.version());
  }
}
