package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.MovementQueries;
import sk.therasuite.accommodation.infrastructure.MovementQueries.MovementRow;
import sk.therasuite.businessday.application.BusinessDateProvider;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.api.ConditionalGet;

/** Príchody a odchody recepcie v daný deň (offline dáta pre desktopového klienta). */
@Service
public class ReceptionMovementsService {

  public record PayerRef(long id, String name) {}

  public record Movement(
      long reservationId,
      String reservationNumber,
      String guestName,
      Integer birthYear,
      int guestCount,
      String roomNumber,
      Integer bedNumber,
      LocalDate arrivalDate,
      LocalDate departureDate,
      String status,
      PayerRef payer) {}

  public record Movements(
      long receptionId, LocalDate date, List<Movement> arrivals, List<Movement> departures) {}

  private final MovementQueries movements;
  private final AccommodationQueries accommodation;
  private final PermissionChecker permissions;
  private final BusinessDateProvider businessDate;

  public ReceptionMovementsService(
      MovementQueries movements,
      AccommodationQueries accommodation,
      PermissionChecker permissions,
      BusinessDateProvider businessDate) {
    this.movements = movements;
    this.accommodation = accommodation;
    this.permissions = permissions;
    this.businessDate = businessDate;
  }

  /** Zadaný dátum, inak obchodný deň recepcie. */
  private LocalDate resolve(long receptionId, LocalDate date) {
    return date != null ? date : businessDate.current(receptionId);
  }

  /** Odtlačok pre ETag; kontroluje oprávnenie ako {@link #movements}, nič nečíta ani neauditúje. */
  @Transactional(readOnly = true)
  public String etag(long userId, long receptionId, LocalDate date) {
    permissions.require(Permissions.PLAN_VIEW, receptionId);
    return ConditionalGet.etag(
        "movements",
        userId,
        receptionId,
        resolve(receptionId, date),
        accommodation.changeSeqToken(receptionId));
  }

  @AuditedRead(operation = "RECEPTION_MOVEMENTS", scope = DataScope.NAME)
  @Transactional(readOnly = true)
  public AuditedReadResult<Movements> movements(long receptionId, LocalDate date) {
    permissions.require(Permissions.PLAN_VIEW, receptionId);
    LocalDate day = resolve(receptionId, date);
    List<MovementRow> arrivals = movements.arrivals(receptionId, day);
    List<MovementRow> departures = movements.departures(receptionId, day);
    Set<Long> clientIds = new LinkedHashSet<>();
    for (List<MovementRow> rows : List.of(arrivals, departures)) {
      rows.stream().map(MovementRow::clientId).filter(id -> id != null).forEach(clientIds::add);
    }
    return new AuditedReadResult<>(
        new Movements(
            receptionId,
            day,
            arrivals.stream().map(ReceptionMovementsService::view).toList(),
            departures.stream().map(ReceptionMovementsService::view).toList()),
        clientIds);
  }

  private static Movement view(MovementRow r) {
    return new Movement(
        r.reservationId(),
        r.reservationNumber(),
        AccommodationPlanService.label(r.lastName(), r.firstName()),
        r.birthYear(),
        r.guestCount(),
        r.roomNumber(),
        r.bedNumber(),
        r.arrivalDate(),
        r.departureDate(),
        r.status(),
        new PayerRef(r.payerId(), r.payerName()));
  }
}
