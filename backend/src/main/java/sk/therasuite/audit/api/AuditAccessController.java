package sk.therasuite.audit.api;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.audit.application.AuditQueryService;
import sk.therasuite.audit.application.AuditViews.AccessView;
import sk.therasuite.audit.application.AuditViews.PageView;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;

/** Kto a kedy čítal údaje klienta (HLD kap. 7.4); vyžaduje AUDIT_VIEW. */
@RestController
@RequestMapping("/api/audit/clients")
public class AuditAccessController {

  private final AuditQueryService service;
  private final PermissionChecker permissions;

  public AuditAccessController(AuditQueryService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping("/{clientId}/access")
  public PageView<AccessView> access(
      @PathVariable long clientId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    permissions.require(Permissions.AUDIT_VIEW);
    return service.clientAccesses(clientId, from, to, page, size);
  }
}
