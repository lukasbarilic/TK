package sk.therasuite.accommodation.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.PlanViews.Plan;
import sk.therasuite.accommodation.application.StayMoveService;
import sk.therasuite.accommodation.application.StayMoveService.MoveItem;
import sk.therasuite.accommodation.application.StayMoveService.ResizeCommand;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;

/**
 * Sťahovanie, zmena dĺžky, dávka presunov a výmena pobytov (UC7–UC11, UC19). Odpoveď je plán
 * dotknutých izieb. Rozsah na recepciu kontroluje služba.
 */
@RestController
@RequestMapping("/api/stays")
public class StayMoveController {

  /** Presun celého pobytu, alebo od dňa {@code fromDate}; {@code version} je nepovinná. */
  public record MoveRequest(
      @NotNull Long targetBedId, LocalDate fromDate, Long version, List<String> confirm) {}

  /** Zmena konca pobytu a/alebo zámku. */
  public record PatchRequest(
      LocalDate to, Boolean locked, @NotNull Long version, List<String> confirm) {}

  /** Položka dávky presunov. */
  public record BatchItem(
      @NotNull Long stayId, @NotNull Long targetBedId, LocalDate fromDate, Long version) {}

  public record SwapRequest(@NotNull Long stayIdA, @NotNull Long stayIdB, List<String> confirm) {}

  private final StayMoveService service;
  private final PermissionChecker permissions;

  public StayMoveController(StayMoveService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @PostMapping("/{id}/move")
  public Plan move(@PathVariable long id, @Valid @RequestBody MoveRequest body) {
    permissions.require(Permissions.STAY_MOVE);
    return service.move(
        id, new MoveItem(id, body.targetBedId(), body.fromDate(), body.version()), body.confirm());
  }

  @PatchMapping("/{id}")
  public Plan patch(@PathVariable long id, @Valid @RequestBody PatchRequest body) {
    permissions.require(Permissions.STAY_MOVE);
    return service.resize(
        id, new ResizeCommand(body.version(), body.to(), body.locked(), body.confirm()));
  }

  /** Telo je zoznam presunov; potvrdené upozornenia idú v parametri {@code confirm}. */
  @PostMapping("/moves:batch")
  public Plan batch(
      @NotEmpty @RequestBody List<@Valid BatchItem> body,
      @RequestParam(name = "confirm", required = false) List<String> confirm) {
    permissions.require(Permissions.STAY_MOVE);
    return service.moveBatch(
        body.stream()
            .map(i -> new MoveItem(i.stayId(), i.targetBedId(), i.fromDate(), i.version()))
            .toList(),
        confirm);
  }

  @PostMapping("/swap")
  public Plan swap(@Valid @RequestBody SwapRequest body) {
    permissions.require(Permissions.STAY_MOVE);
    return service.swap(body.stayIdA(), body.stayIdB(), body.confirm());
  }
}
