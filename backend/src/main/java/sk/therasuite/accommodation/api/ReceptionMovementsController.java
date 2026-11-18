package sk.therasuite.accommodation.api;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.ReceptionMovementsService;
import sk.therasuite.accommodation.application.ReceptionMovementsService.Movements;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.api.ConditionalGet;

/** Príchody a odchody recepcie (oprávnenie PLAN_VIEW pre recepciu kontroluje služba). */
@RestController
@RequestMapping("/api/receptions/{id}/movements")
public class ReceptionMovementsController {

  private final ReceptionMovementsService service;

  public ReceptionMovementsController(ReceptionMovementsService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<Movements> movements(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
      @PathVariable long id,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    String etag = service.etag(user.id(), id, date);
    return ConditionalGet.respond(ifNoneMatch, etag, () -> service.movements(id, date).value());
  }
}
