package sk.therasuite.iam.application;

import java.time.LocalDate;
import java.util.List;

/** Prihlásený používateľ, jeho roly a efektívne oprávnenia. */
public record MeView(
    Long id,
    String username,
    String displayName,
    String email,
    String language,
    boolean mustChangePassword,
    Long defaultReceptionId,
    List<RoleView> roles,
    List<PermissionView> permissions) {

  public record RoleView(
      String code,
      String name,
      Long centreId,
      Long receptionId,
      LocalDate validFrom,
      LocalDate validTo) {}

  /** Efektívne oprávnenie: najvyšší limit a všetky rozsahy, v ktorých platí. */
  public record PermissionView(String code, Integer limit, List<Scope> scopes) {}

  /** {@code null} = všetky strediská / recepcie. */
  public record Scope(Long centreId, Long receptionId) {}
}
