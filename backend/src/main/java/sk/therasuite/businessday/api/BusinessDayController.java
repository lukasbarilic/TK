package sk.therasuite.businessday.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.businessday.application.BusinessDayService;
import sk.therasuite.businessday.application.BusinessDayService.DayView;
import sk.therasuite.businessday.application.BusinessDayService.Preview;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;

/** Obchodný deň a uzávierka; všetko vyžaduje BUSINESS_DAY_CLOSE s rozsahom na danú recepciu. */
@RestController
@RequestMapping("/api/receptions/{id}/business-day")
public class BusinessDayController {

  private final BusinessDayService service;
  private final PermissionChecker permissions;

  public BusinessDayController(BusinessDayService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping
  public DayView get(@PathVariable long id) {
    permissions.require(Permissions.BUSINESS_DAY_CLOSE, id);
    return service.current(id);
  }

  @PostMapping("/close/preview")
  public Preview preview(@PathVariable long id) {
    permissions.require(Permissions.BUSINESS_DAY_CLOSE, id);
    return service.preview(id);
  }

  @PostMapping("/close")
  public DayView close(@PathVariable long id) {
    permissions.require(Permissions.BUSINESS_DAY_CLOSE, id);
    return service.close(id);
  }
}
