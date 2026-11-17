package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.Collection;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import sk.therasuite.businessday.infrastructure.BusinessDayQueries;
import sk.therasuite.shared.application.ApiException;

/** Obchodný deň z tabuľky business_day. */
@Service
public class DatabaseBusinessDateProvider implements BusinessDateProvider {

  private final BusinessDayQueries queries;

  public DatabaseBusinessDateProvider(BusinessDayQueries queries) {
    this.queries = queries;
  }

  @Override
  public LocalDate current(long receptionId) {
    return queries
        .findOpen(receptionId)
        .orElseThrow(() ->
            new ApiException(
                HttpStatus.NOT_FOUND,
                "NOT_FOUND",
                "Recepcia " + receptionId + " nemá otvorený obchodný deň."))
        .businessDate();
  }

  @Override
  public LocalDate currentForRoom(long roomId) {
    long reception =
        queries
            .receptionOfRoom(roomId)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Izba sa nenašla."));
    return current(reception);
  }

  @Override
  public LocalDate latestOf(Collection<Long> roomIds) {
    return roomIds.stream()
        .map(this::currentForRoom)
        .max(LocalDate::compareTo)
        .orElseThrow(() -> new IllegalArgumentException("Zoznam izieb je prázdny."));
  }
}
