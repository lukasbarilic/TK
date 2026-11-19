package sk.therasuite.reservation.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.ReservationStayGateway;
import sk.therasuite.accommodation.application.ReservationStayGateway.Snapshot;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.reservation.application.ReservationViews.Detail;
import sk.therasuite.reservation.domain.ReservationStatus;
import sk.therasuite.reservation.infrastructure.Reservation;
import sk.therasuite.reservation.infrastructure.ReservationRepository;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.application.ApiException;

/**
 * Stavy rezervačnej karty: Rezervovaná → Prítomný → Odhlásený, z Rezervovanej Stornovaná alebo
 * Neprišiel, a opravy. Zmena stavu pridelenie lôžka nemení; pobyty sa uvoľňujú len pri storne,
 * neprišiel a skoršom odchode.
 */
@Service
public class ReservationStatusService {

  public static final String EARLY_DEPARTURE = "EARLY_DEPARTURE";
  private static final int MAX_REASON_LENGTH = 500;

  private final ReservationService reservations;
  private final ReservationRepository repository;
  private final ReservationStayGateway stays;
  private final PermissionChecker permissions;

  public ReservationStatusService(
      ReservationService reservations,
      ReservationRepository repository,
      ReservationStayGateway stays,
      PermissionChecker permissions) {
    this.reservations = reservations;
    this.repository = repository;
    this.stays = stays;
    this.permissions = permissions;
  }

  @AuditedOperation(operation = "RESERVATION_CHECK_IN")
  @Transactional
  public Detail checkIn(long id, long version) {
    Reservation r = begin(id, version, Permissions.RESERVATION_STATUS);
    requireStatus(r, ReservationStatus.RESERVED);
    Snapshot snapshot = stays.snapshot(id);
    requireReceptions(snapshot, Permissions.RESERVATION_STATUS);
    int needed = r.isExclusiveRoomUse() ? 1 : Math.max(snapshot.activeGuests(), 1);
    if (snapshot.assignedBeds() < needed) {
      throw conflict("NO_BED_ASSIGNED", "Karta nemá pridelené lôžko pre všetkých hostí.");
    }
    if (r.getArrivalDate().isAfter(stays.businessDate(snapshot))) {
      throw conflict("ARRIVAL_IN_FUTURE", "Dátum príchodu je neskôr ako obchodný deň recepcie.");
    }
    r.checkIn(Instant.now());
    return finish(r, snapshot, false);
  }

  /**
   * Skorší odchod (obchodný deň je pred dátumom odchodu) skráti pobyty k obchodnému dňu; vyžaduje
   * potvrdenie upozornenia {@code EARLY_DEPARTURE}.
   */
  @AuditedOperation(operation = "RESERVATION_CHECK_OUT")
  @Transactional
  public Detail checkOut(long id, long version, List<String> confirm) {
    Reservation r = begin(id, version, Permissions.RESERVATION_STATUS);
    requireStatus(r, ReservationStatus.CHECKED_IN);
    Snapshot snapshot = stays.snapshot(id);
    requireReceptions(snapshot, Permissions.RESERVATION_STATUS);
    boolean early = stays.businessDate(snapshot).isBefore(r.getDepartureDate());
    if (early && (confirm == null || !confirm.contains(EARLY_DEPARTURE))) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "CONFIRMATION_REQUIRED",
          "Operácia vyžaduje potvrdenie upozornení.",
          Map.of(
              "confirmable",
              true,
              "warnings",
              List.of(
                  Map.of(
                      "code",
                      EARLY_DEPARTURE,
                      "message",
                      "Hosť odchádza skôr, než je dátum odchodu; pobyty sa skrátia."))));
    }
    r.checkOut(Instant.now());
    return finish(r, snapshot, early);
  }

  @AuditedOperation(operation = "RESERVATION_CANCEL")
  @Transactional
  public Detail cancel(long id, long version, String reason) {
    String trimmed = reason == null ? "" : reason.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_REASON_LENGTH) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          "Dôvod storna je povinný (najviac " + MAX_REASON_LENGTH + " znakov).");
    }
    Reservation r = begin(id, version, Permissions.RESERVATION_STATUS);
    requireStatus(r, ReservationStatus.RESERVED);
    Snapshot snapshot = stays.snapshot(id);
    requireReceptions(snapshot, Permissions.RESERVATION_STATUS);
    r.cancel(Instant.now(), trimmed);
    return finish(r, snapshot, true);
  }

  @AuditedOperation(operation = "RESERVATION_NO_SHOW")
  @Transactional
  public Detail noShow(long id, long version) {
    Reservation r = begin(id, version, Permissions.RESERVATION_STATUS);
    requireStatus(r, ReservationStatus.RESERVED);
    Snapshot snapshot = stays.snapshot(id);
    requireReceptions(snapshot, Permissions.RESERVATION_STATUS);
    if (r.getArrivalDate().isAfter(stays.businessDate(snapshot))) {
      throw conflict("NO_SHOW_IN_FUTURE", "Karta s príchodom v budúcnosti nemôže byť neprišiel.");
    }
    r.markNoShow();
    return finish(r, snapshot, true);
  }

  /** Oprava: zrušenie príchodu, len v obchodný deň príchodu. */
  @AuditedOperation(operation = "RESERVATION_UNDO_CHECK_IN")
  @Transactional
  public Detail undoCheckIn(long id, long version) {
    Reservation r = begin(id, version, Permissions.RESERVATION_STATUS_FIX);
    requireStatus(r, ReservationStatus.CHECKED_IN);
    Snapshot snapshot = stays.snapshot(id);
    requireReceptions(snapshot, Permissions.RESERVATION_STATUS_FIX);
    if (!r.getArrivalDate().equals(stays.businessDate(snapshot))) {
      throw conflict(
          "UNDO_CHECK_IN_NOT_ALLOWED", "Príchod možno zrušiť len v obchodný deň príchodu.");
    }
    r.undoCheckIn();
    return finish(r, snapshot, false);
  }

  /** Oprava: z Neprišiel späť na Rezervovanú; uvoľnené lôžko sa nevracia automaticky. */
  @AuditedOperation(operation = "RESERVATION_REVERT_NO_SHOW")
  @Transactional
  public Detail revertNoShow(long id, long version) {
    Reservation r = begin(id, version, Permissions.RESERVATION_STATUS_FIX);
    requireStatus(r, ReservationStatus.NO_SHOW);
    Snapshot snapshot = stays.snapshot(id);
    requireReceptions(snapshot, Permissions.RESERVATION_STATUS_FIX);
    r.revertNoShow();
    return finish(r, snapshot, false);
  }

  private Reservation begin(long id, long version, String permission) {
    permissions.require(permission);
    Reservation r = reservations.load(id);
    if (r.getVersion() != version) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "CONFLICT",
          "Záznam medzitým zmenil iný používateľ. Načítajte ho znova.");
    }
    return r;
  }

  /** Oprávnenie musí platiť pre každú recepciu, v ktorej má karta pobyty. */
  private void requireReceptions(Snapshot snapshot, String permission) {
    Set<Long> receptions = snapshot.receptionIds();
    receptions.forEach(reception -> permissions.require(permission, reception));
  }

  private Detail finish(Reservation r, Snapshot snapshot, boolean release) {
    repository.saveAndFlush(r);
    if (release) {
      stays.releaseFromBusinessDay(snapshot);
    }
    stays.publishPlanChanged(snapshot);
    return reservations.detail(r);
  }

  private static void requireStatus(Reservation r, ReservationStatus expected) {
    if (r.getStatus() != expected) {
      throw conflict(
          "INVALID_STATUS_TRANSITION",
          "Zo stavu "
              + r.getStatus()
              + " túto operáciu nemožno vykonať (očakáva sa "
              + expected
              + ").");
    }
  }

  private static ApiException conflict(String code, String message) {
    return new ApiException(HttpStatus.CONFLICT, code, message);
  }
}
