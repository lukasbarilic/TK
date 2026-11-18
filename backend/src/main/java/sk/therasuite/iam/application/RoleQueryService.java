package sk.therasuite.iam.application;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.iam.infrastructure.IamQueries;
import sk.therasuite.iam.infrastructure.IamQueries.PermissionRow;
import sk.therasuite.iam.infrastructure.IamQueries.RolePermissionRow;

/** Len čítanie rolí a oprávnení (bez osobných údajov, preto bez auditu). */
@Service
public class RoleQueryService {

  public record RoleView(
      String code,
      String name,
      String description,
      boolean systemRole,
      List<RolePermissionRow> permissions) {}

  private final IamQueries queries;

  public RoleQueryService(IamQueries queries) {
    this.queries = queries;
  }

  @Transactional(readOnly = true)
  public List<RoleView> roles() {
    var perms = queries.rolePermissions();
    return queries.allRoles().stream()
        .map(r ->
            new RoleView(
                r.code(),
                r.name(),
                r.description(),
                r.systemRole(),
                perms.getOrDefault(r.id(), List.of())))
        .toList();
  }

  @Transactional(readOnly = true)
  public List<PermissionRow> permissions() {
    return queries.allPermissions();
  }
}
