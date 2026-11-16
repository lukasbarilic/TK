package sk.therasuite.iam.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.iam.application.PermissionChecker;
import sk.therasuite.iam.application.UserAdminService;
import sk.therasuite.iam.application.UserAdminService.CreatedUser;
import sk.therasuite.iam.application.UserAdminService.RoleAssignmentInput;
import sk.therasuite.iam.application.UserAdminService.UserView;
import sk.therasuite.iam.domain.Permissions;
import sk.therasuite.iam.domain.UserStatus;

/** Správa používateľov; všetko vyžaduje oprávnenie USER_ADMIN. */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

  public record CreateUserRequest(
      @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9._-]+") String username,
      @NotBlank @Size(max = 200) String displayName,
      @Email @Size(max = 200) String email,
      @Size(max = 10) String language,
      Long defaultReceptionId,
      @Valid List<RoleAssignmentInput> roles) {}

  /** Neuvedené (null) polia sa nemenia; status je ACTIVE alebo DISABLED. */
  public record UpdateUserRequest(
      @Size(min = 1, max = 200) String displayName,
      @Email @Size(max = 200) String email,
      @Size(max = 10) String language,
      UserStatus status,
      Long defaultReceptionId) {}

  public record RolesRequest(@NotNull @Valid List<RoleAssignmentInput> roles) {}

  public record PasswordResult(String oneTimePassword) {}

  private final UserAdminService service;
  private final PermissionChecker permissions;

  public AdminUserController(UserAdminService service, PermissionChecker permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping
  public List<UserView> list() {
    permissions.require(Permissions.USER_ADMIN);
    return service.list();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedUser create(@Valid @RequestBody CreateUserRequest body) {
    permissions.require(Permissions.USER_ADMIN);
    return service.create(
        body.username(),
        body.displayName(),
        body.email(),
        body.language(),
        body.defaultReceptionId(),
        body.roles());
  }

  @PatchMapping("/{id}")
  public UserView update(@PathVariable long id, @Valid @RequestBody UpdateUserRequest body) {
    permissions.require(Permissions.USER_ADMIN);
    return service.update(
        id,
        body.displayName(),
        body.email(),
        body.language(),
        body.status(),
        body.defaultReceptionId());
  }

  @PutMapping("/{id}/roles")
  public UserView roles(@PathVariable long id, @Valid @RequestBody RolesRequest body) {
    permissions.require(Permissions.USER_ADMIN);
    return service.replaceRoles(id, body.roles());
  }

  @PostMapping("/{id}/reset-password")
  public PasswordResult resetPassword(@PathVariable long id) {
    permissions.require(Permissions.USER_ADMIN);
    return new PasswordResult(service.resetPassword(id));
  }

  @PostMapping("/{id}/unlock")
  public UserView unlock(@PathVariable long id) {
    permissions.require(Permissions.USER_ADMIN);
    return service.unlock(id);
  }
}
