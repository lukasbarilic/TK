package sk.therasuite.accommodation.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.accommodation.application.RoomAvailabilityService;
import sk.therasuite.accommodation.application.StayViews.AvailableRoomView;

/** Voľné izby pre kartu (oprávnenie STAY_CREATE kontroluje služba). */
@RestController
@RequestMapping("/api/rooms")
public class RoomAvailabilityController {

  private final RoomAvailabilityService service;

  public RoomAvailabilityController(RoomAvailabilityService service) {
    this.service = service;
  }

  @GetMapping("/available")
  public List<AvailableRoomView> available(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Long categoryId,
      @RequestParam(defaultValue = "false") boolean allCategories,
      @RequestParam(required = false) Long receptionId,
      @RequestParam(defaultValue = "false") boolean extraBeds) {
    return service.available(from, to, categoryId, allCategories, receptionId, extraBeds);
  }
}
