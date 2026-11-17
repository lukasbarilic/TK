package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.Collection;

/**
 * Jediný zdroj „dnešného“ dátumu pre pravidlá s dátumom (HLD kap. 5.8). Mimo modulu businessday sa
 * nesmie používať {@code LocalDate.now()}.
 */
public interface BusinessDateProvider {

  /** Otvorený obchodný deň recepcie. */
  LocalDate current(long receptionId);

  /** Obchodný deň recepcie, ktorej patrí izba (cez building.reception_id). */
  LocalDate currentForRoom(long roomId);

  /** Neskorší z obchodných dní recepcií daných izieb (presun medzi recepciami). */
  LocalDate latestOf(Collection<Long> roomIds);
}
