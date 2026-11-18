package sk.therasuite.accommodation.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.accommodation.infrastructure.AccommodationQueries;
import sk.therasuite.accommodation.infrastructure.OfflineCardQueries;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.shared.api.ConditionalGet;

/** Výber ID kariet pre offline detail (oprávnenie RESERVATION_VIEW v rozsahu recepcie). */
@Service
public class OfflineCardSelection {

  private final OfflineCardQueries queries;
  private final AccommodationQueries accommodation;
  private final PermissionChecker permissions;

  public OfflineCardSelection(
      OfflineCardQueries queries,
      AccommodationQueries accommodation,
      PermissionChecker permissions) {
    this.queries = queries;
    this.accommodation = accommodation;
    this.permissions = permissions;
  }

  /** Odtlačok pre ETag okna; kontroluje oprávnenie, nič nečíta ani neauditúje. */
  @Transactional(readOnly = true)
  public String etag(long userId, long receptionId, LocalDate from, LocalDate to) {
    permissions.require(Permissions.RESERVATION_VIEW, receptionId);
    return ConditionalGet.etag(
        "offline-reservations",
        userId,
        receptionId,
        from,
        to,
        accommodation.changeSeqToken(receptionId));
  }

  @Transactional(readOnly = true)
  public List<Long> inWindow(long receptionId, LocalDate from, LocalDate to) {
    permissions.require(Permissions.RESERVATION_VIEW, receptionId);
    return queries.cardIdsInWindow(receptionId, from, to);
  }

  /** Z {@code ids} ponechá existujúce karty, ktorých recepciu má používateľ v rozsahu. */
  @Transactional(readOnly = true)
  public List<Long> visible(List<Long> ids) {
    permissions.require(Permissions.RESERVATION_VIEW);
    Map<Long, Boolean> allowed = new HashMap<>();
    return queries.receptionsOfCards(ids).entrySet().stream()
        .filter(e ->
            e.getValue().stream()
                .anyMatch(rec ->
                    allowed.computeIfAbsent(
                        rec, r -> permissions.has(Permissions.RESERVATION_VIEW, r))))
        .map(Map.Entry::getKey)
        .toList();
  }
}
