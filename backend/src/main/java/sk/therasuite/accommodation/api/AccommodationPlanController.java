package sk.therasuite.accommodation.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.AccommodationPlanService;
import sk.therasuite.accommodation.application.AccommodationPlanService.PlanFilter;
import sk.therasuite.accommodation.application.PlanViews.Plan;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.api.ConditionalGet;

/** Plán ubytovania (oprávnenie PLAN_VIEW a rozsah na recepciu kontroluje služba). */
@RestController
@RequestMapping("/api/accommodation-plan")
public class AccommodationPlanController {

  private final AccommodationPlanService service;

  public AccommodationPlanController(AccommodationPlanService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<Plan> plan(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) List<Long> buildingIds,
      @RequestParam(required = false) List<Long> roomIds,
      @RequestParam(required = false) Long receptionId,
      @RequestParam(defaultValue = "false") boolean freeOnly,
      @RequestParam(defaultValue = "false") boolean fullyFree) {
    PlanFilter filter = new PlanFilter(buildingIds, roomIds, receptionId, freeOnly, fullyFree);
    // ETag sa overí pred čítaním plánu: pri 304 sa plán nenačíta ani sa nezapíše audit čítania.
    String etag = service.planEtag(user.id(), from, to, filter);
    return ConditionalGet.respond(ifNoneMatch, etag, () -> service.plan(from, to, filter).value());
  }
}
