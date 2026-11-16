package sk.therasuite.iam.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sk.therasuite.iam.application.AuthService;
import sk.therasuite.iam.application.MeService;
import sk.therasuite.iam.application.MeView;
import sk.therasuite.shared.AuthenticatedUser;

@RestController
@RequestMapping("/api/me")
public class MeController {

  public record ChangePasswordRequest(
      @NotBlank String currentPassword,
      @NotBlank @Size(min = AuthService.MIN_PASSWORD_LENGTH, max = 128) String newPassword) {}

  private final MeService me;
  private final AuthService auth;

  public MeController(MeService me, AuthService auth) {
    this.me = me;
    this.auth = auth;
  }

  @GetMapping
  public MeView me(@AuthenticationPrincipal AuthenticatedUser user) {
    return me.view(user.id());
  }

  @PostMapping("/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody ChangePasswordRequest body) {
    auth.changePassword(user.id(), body.currentPassword(), body.newPassword());
  }
}
