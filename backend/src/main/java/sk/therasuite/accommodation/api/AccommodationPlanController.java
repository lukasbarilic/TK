package sk.therasuite.accommodation.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.AccommodationPlanService;
import sk.therasuite.accommodation.application.AccommodationPlanService.PlanFilter;
import sk.therasuite.accommodation.application.PlanViews.Plan;

/** Plán ubytovania (oprávnenie PLAN_VIEW a rozsah na recepciu kontroluje služba). */
@RestController
@RequestMapping("/api/accommodation-plan")
public class AccommodationPlanController {

  private final AccommodationPlanService service;

  public AccommodationPlanController(AccommodationPlanService service) {
    this.service = service;
  }

  @GetMapping
  public Plan plan(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) List<Long> buildingIds,
      @RequestParam(required = false) List<Long> roomIds,
      @RequestParam(required = false) Long receptionId,
      @RequestParam(defaultValue = "false") boolean freeOnly,
      @RequestParam(defaultValue = "false") boolean fullyFree) {
    return service
        .plan(from, to, new PlanFilter(buildingIds, roomIds, receptionId, freeOnly, fullyFree))
        .value();
  }
}
