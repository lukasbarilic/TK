package sk.therasuite.iam.application;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.IamQueries.Grant;
import sk.therasuite.shared.AuthenticatedUser;

/**
 * Kontrola oprávnení aktuálneho používateľa (HLD kap. 8.2, 8.3). Kontroluje oprávnenia, nikdy názvy
 * rolí; zohľadňuje platnosť pridelenia a rozsah na stredisko / recepciu.
 */
@Service
public class PermissionChecker {

  private final IamQueries queries;

  public PermissionChecker(IamQueries queries) {
    this.queries = queries;
  }

  /** Vyžaduje oprávnenie v ľubovoľnom rozsahu. */
  public void require(String permission) {
    if (matching(permission, null).isEmpty()) {
      throw new AccessDeniedException("Chýba oprávnenie " + permission);
    }
  }

  /** Vyžaduje oprávnenie platné pre danú recepciu (a stredisko, do ktorého recepcia patrí). */
  public void require(String permission, long receptionId) {
    if (matching(permission, receptionId).isEmpty()) {
      throw new AccessDeniedException(
          "Chýba oprávnenie " + permission + " pre recepciu " + receptionId);
    }
  }

  /** {@code true}, ak má používateľ oprávnenie platné pre danú recepciu. */
  public boolean has(String permission, long receptionId) {
    return !matching(permission, receptionId).isEmpty();
  }

  /** Najvyšší limit oprávnenia platný pre danú recepciu; bez práva vyhodí výnimku. */
  public int limit(String permission, long receptionId) {
    List<Grant> grants = matching(permission, receptionId);
    if (grants.isEmpty()) {
      throw new AccessDeniedException(
          "Chýba oprávnenie " + permission + " pre recepciu " + receptionId);
    }
    return maxLimit(grants);
  }

  /** Najvyšší limit oprávnenia zo všetkých platných rolí; bez práva vyhodí výnimku. */
  public int limit(String permission) {
    List<Grant> grants = matching(permission, null);
    if (grants.isEmpty()) {
      throw new AccessDeniedException("Chýba oprávnenie " + permission);
    }
    return maxLimit(grants);
  }

  static int maxLimit(List<Grant> grants) {
    return grants.stream()
        .mapToInt(g -> g.limit() == null ? Permissions.MAX_LIMIT : g.limit())
        .max()
        .orElse(Permissions.MAX_LIMIT);
  }

  private List<Grant> matching(String permission, Long receptionId) {
    List<Grant> grants =
        queries.grants(currentUserId(), LocalDate.now()).stream()
            .filter(g -> g.code().equals(permission))
            .toList();
    if (receptionId == null) {
      return grants;
    }
    Set<Long> centres = new HashSet<>(queries.centreIdsOfReception(receptionId));
    return grants.stream()
        .filter(g -> g.receptionId() == null || g.receptionId().equals(receptionId))
        .filter(g -> g.centreId() == null || centres.contains(g.centreId()))
        .toList();
  }

  private static long currentUserId() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return user.id();
    }
    throw new AccessDeniedException("Používateľ nie je prihlásený");
  }
}
