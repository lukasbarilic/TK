package sk.therasuite.businessday.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.businessday.application.BusinessDayReportService;
import sk.therasuite.businessday.application.BusinessDayReportService.ArchiveDetail;
import sk.therasuite.businessday.application.BusinessDayReportService.ArchiveItem;
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
  private final BusinessDayReportService reports;
  private final PermissionChecker permissions;

  public BusinessDayController(
      BusinessDayService service, BusinessDayReportService reports, PermissionChecker permissions) {
    this.service = service;
    this.reports = reports;
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

  /** Archív reportov: stačí BUSINESS_DAY_CLOSE alebo REPORT_VIEW pre danú recepciu. */
  @GetMapping("/reports")
  public List<ArchiveItem> reports(
      @PathVariable long id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    requireArchiveAccess(id);
    return reports.list(id, from, to);
  }

  @GetMapping("/reports/{date}")
  public ArchiveDetail report(
      @PathVariable long id,
      @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    requireArchiveAccess(id);
    return reports.detail(id, date);
  }

  private void requireArchiveAccess(long receptionId) {
    if (!permissions.has(Permissions.BUSINESS_DAY_CLOSE, receptionId)
        && !permissions.has(Permissions.REPORT_VIEW, receptionId)) {
      throw new AccessDeniedException("Chýba oprávnenie na report uzávierky");
    }
  }
}
