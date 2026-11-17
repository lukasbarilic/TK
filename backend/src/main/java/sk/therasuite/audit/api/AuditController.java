package sk.therasuite.audit.api;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.audit.application.AuditQueryService;
import sk.therasuite.audit.application.AuditViews.EventDetail;
import sk.therasuite.audit.application.AuditViews.EventSummary;
import sk.therasuite.audit.application.AuditViews.PageView;
import sk.therasuite.audit.infrastructure.AuditRepository.Filter;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;

/** Čítanie audit záznamov; všetko vyžaduje oprávnenie AUDIT_VIEW. */
@RestController
@RequestMapping("/api/audit/events")
public class AuditController {

  private final AuditQueryService service;
  private final PermissionChecker permissions;

  public AuditController(AuditQueryService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping
  public PageView<EventSummary> search(
      @RequestParam(required = false) String type,
      @RequestParam(required = false) String entityType,
      @RequestParam(required = false) Long entityId,
      @RequestParam(required = false) Long userId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) String operation,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    permissions.require(Permissions.AUDIT_VIEW);
    return service.search(
        new Filter(type, entityType, entityId, userId, from, to, operation), page, size);
  }

  @GetMapping("/{id}")
  public EventDetail detail(@PathVariable long id) {
    permissions.require(Permissions.AUDIT_VIEW);
    return service.detail(id);
  }
}
