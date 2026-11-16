package sk.therasuite.blocking.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.blocking.infrastructure.RoomBlockRepository;

/** Čítanie blokácií izieb. */
@Service
@Transactional(readOnly = true)
public class RoomBlockService {

  private final RoomBlockRepository blocks;

  public RoomBlockService(RoomBlockRepository blocks) {
    this.blocks = blocks;
  }

  public List<RoomBlockView> find(LocalDate from, LocalDate to, Long roomId) {
    return blocks.findOverlapping(from, to, roomId).stream()
        .map(b ->
            new RoomBlockView(
                b.getId(),
                b.getRoomId(),
                b.getReasonId(),
                b.getDateFrom(),
                b.getDateTo(),
                b.getNote()))
        .toList();
  }
}
