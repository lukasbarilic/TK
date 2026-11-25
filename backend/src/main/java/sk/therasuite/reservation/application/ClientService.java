package sk.therasuite.reservation.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.ReservationStayGateway;
import sk.therasuite.reservation.infrastructure.Client;
import sk.therasuite.reservation.infrastructure.ClientRepository;
import sk.therasuite.reservation.infrastructure.ReservationGuest;
import sk.therasuite.reservation.infrastructure.ReservationGuestRepository;
import sk.therasuite.reservation.infrastructure.ReservationQueries;
import sk.therasuite.reservation.infrastructure.ReservationQueries.ClientRow;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/**
 * Vyhľadanie klienta pri zakladaní rezervačnej karty a oprava jeho údajov (oprávnenie kontroluje
 * controller).
 */
@Service
public class ClientService {

  public static final int MAX_LIMIT = 50;

  /** Výsledok vyhľadania: bez kontaktných údajov. */
  public record ClientItem(
      long id, String firstName, String lastName, Integer birthYear, LocalDate lastStayDate) {}

  /** Oprava údajov klienta; nahrádza všetky polia, {@code version} je verzia z detailu karty. */
  public record UpdateCommand(
      long version,
      String firstName,
      String lastName,
      LocalDate birthDate,
      String nationality,
      String email,
      String phone) {}

  /** Údaje klienta po oprave. */
  public record ClientView(
      long id,
      long version,
      String firstName,
      String lastName,
      LocalDate birthDate,
      String nationality,
      String email,
      String phone) {}

  private final ReservationQueries queries;
  private final ClientRepository clients;
  private final ReservationGuestRepository guests;
  private final ReservationStayGateway stays;

  public ClientService(
      ReservationQueries queries,
      ClientRepository clients,
      ReservationGuestRepository guests,
      ReservationStayGateway stays) {
    this.queries = queries;
    this.clients = clients;
    this.guests = guests;
    this.stays = stays;
  }

  /**
   * Oprava údajov klienta (napr. preklep v kontakte). Klient je zdieľaný, preto sa zmena prejaví na
   * všetkých jeho kartách bez ohľadu na ich stav; plán sa obnoví na izbách ich pobytov.
   */
  @AuditedOperation(operation = "CLIENT_UPDATE")
  @Transactional
  public ClientView update(long id, UpdateCommand cmd) {
    Client c =
        clients
            .findById(id)
            .orElseThrow(
                () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Klient neexistuje."));
    if (c.getVersion() != cmd.version()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "CONFLICT",
          "Záznam medzitým zmenil iný používateľ. Načítajte ho znova.");
    }
    String firstName = trimToNull(cmd.firstName());
    String lastName = trimToNull(cmd.lastName());
    if (firstName == null || lastName == null) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Meno a priezvisko sú povinné.");
    }
    String nationality = trimToNull(cmd.nationality());
    c.update(
        firstName,
        lastName,
        cmd.birthDate(),
        nationality == null ? null : nationality.toUpperCase(Locale.ROOT),
        trimToNull(cmd.email()),
        trimToNull(cmd.phone()));
    Client saved = clients.saveAndFlush(c);
    publishPlanChanged(id);
    return new ClientView(
        saved.getId(),
        saved.getVersion(),
        saved.getFirstName(),
        saved.getLastName(),
        saved.getBirthDate(),
        saved.getNationality(),
        saved.getEmail(),
        saved.getPhone());
  }

  /** Meno klienta je na pásikoch plánu, preto sa pošle plan-changed pre izby pobytov jeho kariet. */
  private void publishPlanChanged(long clientId) {
    stays.publishPlanChangedForReservations(
        guests.findByClientId(clientId).stream()
            .map(ReservationGuest::getReservationId)
            .distinct()
            .toList());
  }

  private static String trimToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
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
