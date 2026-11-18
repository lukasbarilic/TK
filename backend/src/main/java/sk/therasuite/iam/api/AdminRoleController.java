package sk.therasuite.iam.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.application.RoleQueryService;
import sk.therasuite.iam.application.RoleQueryService.RoleView;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.iam.infrastructure.IamQueries.PermissionRow;

/** Zoznam rolí a oprávnení pre administráciu; vyžaduje USER_ADMIN. */
@RestController
@RequestMapping("/api/admin")
public class AdminRoleController {

  private final RoleQueryService service;
  private final PermissionChecker permissions;

  public AdminRoleController(RoleQueryService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping("/roles")
  public List<RoleView> roles() {
    permissions.require(Permissions.USER_ADMIN);
    return service.roles();
  }

  @GetMapping("/permissions")
  public List<PermissionRow> permissions() {
    permissions.require(Permissions.USER_ADMIN);
    return service.permissions();
  }
}
