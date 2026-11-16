package sk.therasuite.blocking.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sk.therasuite.blocking.application.RoomBlockService;
import sk.therasuite.blocking.application.RoomBlockView;

/** Čítanie blokácií (zápis je mimo rozsahu P02; zabezpečenie pribudne v P03). */
@RestController
@RequestMapping("/api/room-blocks")
public class RoomBlockController {

  private final RoomBlockService service;

  public RoomBlockController(RoomBlockService service) {
    this.service = service;
  }

  @GetMapping
  public List<RoomBlockView> find(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Long roomId) {
    if (from != null && to != null && !from.isBefore(to)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Parameter 'from' musí byť pred 'to'.");
    }
    return service.find(from, to, roomId);
  }
}
