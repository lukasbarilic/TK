package sk.therasuite.iam.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
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
@RequestMapping("/api/auth")
public class AuthController {

  public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

  private final AuthService auth;
  private final MeService me;
  private final SecurityContextRepository contextRepository;

  public AuthController(
      AuthService auth, MeService me, SecurityContextRepository contextRepository) {
    this.auth = auth;
    this.me = me;
    this.contextRepository = contextRepository;
  }

  @PostMapping("/login")
  public MeView login(
      @Valid @RequestBody LoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    AuthenticatedUser user = auth.login(body.username().trim(), body.password());
    if (request.getSession(false) != null) {
      request.changeSessionId(); // ochrana pred fixáciou relácie
    } else {
      request.getSession(true);
    }
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, request, response);
    return me.view(user.id());
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(HttpServletRequest request) {
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    SecurityContextHolder.clearContext();
  }
}
