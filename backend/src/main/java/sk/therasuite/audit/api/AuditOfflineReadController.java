package sk.therasuite.audit.api;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.audit.application.AuditOfflineReadService;
import sk.therasuite.audit.application.AuditOfflineReadService.BatchRequest;
import sk.therasuite.audit.application.AuditOfflineReadService.BatchResult;

/** Príjem denníka zobrazení z lokálnej kópie (stačí prihlásený používateľ). */
@RestController
@RequestMapping("/api/audit/offline-reads")
public class AuditOfflineReadController {

  private final AuditOfflineReadService service;

  public AuditOfflineReadController(AuditOfflineReadService service) {
    this.service = service;
  }

  @PostMapping
  public BatchResult receive(@Valid @RequestBody BatchRequest request) {
    return service.receive(request.records());
  }
}
