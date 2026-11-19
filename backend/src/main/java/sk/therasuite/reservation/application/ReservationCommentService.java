package sk.therasuite.reservation.application;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.application.ReservationStayGateway;
import sk.therasuite.reservation.application.ReservationViews.CommentView;
import sk.therasuite.reservation.infrastructure.ReservationGuest;
import sk.therasuite.reservation.infrastructure.ReservationGuestRepository;
import sk.therasuite.reservation.infrastructure.ReservationQueries;
import sk.therasuite.reservation.infrastructure.ReservationRepository;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuditedRead;
import sk.therasuite.shared.AuditedReadResult;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.DataScope;
import sk.therasuite.shared.application.ApiException;

/**
 * Komentáre rezervačnej karty (oprávnenia kontroluje controller). Komentár sa nedá upraviť, len
 * skryť; nový aj skrytý komentár mení počet komentárov na pásiku plánu, preto sa pošle {@code
 * plan-changed} (počítadlo zmien recepcie zvyšuje trigger).
 */
@Service
public class ReservationCommentService {

  private final ReservationRepository reservations;
  private final ReservationGuestRepository guests;
  private final ReservationQueries queries;
  private final ReservationStayGateway stays;

  public ReservationCommentService(
      ReservationRepository reservations,
      ReservationGuestRepository guests,
      ReservationQueries queries,
      ReservationStayGateway stays) {
    this.reservations = reservations;
    this.guests = guests;
    this.queries = queries;
    this.stays = stays;
  }

  /** Čítanie komentárov je čítanie karty (rozsah CONTACT). */
  @AuditedRead(operation = "RESERVATION_VIEW", scope = DataScope.CONTACT)
  @Transactional(readOnly = true)
  public AuditedReadResult<List<CommentView>> list(long reservationId) {
    requireCard(reservationId);
    List<CommentView> views =
        queries.comments(reservationId).stream().map(ReservationService::commentView).toList();
    return new AuditedReadResult<>(
        views,
        guests.findByReservationIdOrderById(reservationId).stream()
            .map(ReservationGuest::getClientId)
            .toList());
  }

  @AuditedOperation(operation = "RESERVATION_COMMENT_ADD", useCase = "UC1")
  @Transactional
  public CommentView add(long reservationId, String text) {
    String trimmed = text == null ? "" : text.strip();
    if (trimmed.isEmpty() || trimmed.length() > ReservationService.MAX_NOTE_LENGTH) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_FAILED",
          "Komentár je povinný (najviac " + ReservationService.MAX_NOTE_LENGTH + " znakov).");
    }
    requireCard(reservationId);
    CommentView view =
        ReservationService.commentView(
            queries.insertComment(reservationId, currentUserId(), trimmed));
    stays.publishPlanChanged(stays.snapshot(reservationId));
    return view;
  }

  @AuditedOperation(operation = "RESERVATION_COMMENT_HIDE", useCase = "UC1")
  @Transactional
  public void hide(long reservationId, long commentId) {
    requireCard(reservationId);
    queries
        .hideComment(reservationId, commentId, currentUserId())
        .orElseThrow(
            () -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Komentár neexistuje."));
    stays.publishPlanChanged(stays.snapshot(reservationId));
  }

  private void requireCard(long id) {
    if (!reservations.existsById(id)) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Karta neexistuje.");
    }
  }

  private static long currentUserId() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return user.id();
    }
    throw new AccessDeniedException("Nie je prihlásený");
  }
}
