package sk.therasuite.reservation.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.reservation.infrastructure.ReservationQueries;
import sk.therasuite.reservation.infrastructure.ReservationQueries.ClientRow;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/** Vyhľadanie klienta pri zakladaní rezervačnej karty (oprávnenie kontroluje controller). */
@Service
public class ClientService {

  public static final int MAX_LIMIT = 50;

  /** Výsledok vyhľadania: bez kontaktných údajov. */
  public record ClientItem(
      long id, String firstName, String lastName, Integer birthYear, LocalDate lastStayDate) {}

  private final ReservationQueries queries;

  public ClientService(ReservationQueries queries) {
    this.queries = queries;
  }

  @AuditedRead(operation = "CLIENT_SEARCH", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<List<ClientItem>> search(String q, int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          "Parameter limit musí byť 1–" + MAX_LIMIT + ".");
    }
    List<ClientRow> rows = queries.searchClients(q, limit);
    return new AuditedReadResult<>(
        rows.stream()
            .map(r ->
                new ClientItem(
                    r.id(), r.firstName(), r.lastName(), r.birthYear(), r.lastStayDate()))
            .toList(),
        rows.stream().map(ClientRow::id).toList());
  }
}
