package sk.therasuite.report.api;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.report.application.GuestLocationService;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.api.ConditionalGet;
import sk.therasuite.shared.application.ApiException;

/** Zoznam hostí s miestom ubytovania (JSON, CSV); PDF je mimo rozsahu. */
@RestController
@RequestMapping("/api/reports/guest-locations")
public class GuestLocationController {

  private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

  private final GuestLocationService service;

  public GuestLocationController(GuestLocationService service) {
    this.service = service;
  }

  @GetMapping
  public ResponseEntity<?> guestLocations(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
      @RequestParam(required = false) Long centreId,
      @RequestParam(required = false) Long buildingId,
      @RequestParam(required = false) Long doctorId,
      @RequestParam(defaultValue = "json") String format) {
    switch (format.toLowerCase()) {
      case "json" -> {
        String etag = service.etag(user.id(), date, centreId, buildingId, doctorId);
        return ConditionalGet.respond(
            ifNoneMatch,
            etag,
            () -> service.list(user.id(), date, centreId, buildingId, doctorId).value());
      }
      case "csv" -> {
        byte[] body = service.exportCsv(user.id(), date, centreId, buildingId, doctorId).value();
        return ResponseEntity.ok()
            .contentType(CSV)
            .header("Content-Disposition", "attachment; filename=\"guest-locations.csv\"")
            .body(body);
      }
      default ->
          throw new ApiException(
              HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT", "Podporované formáty: json, csv.");
    }
  }
}
