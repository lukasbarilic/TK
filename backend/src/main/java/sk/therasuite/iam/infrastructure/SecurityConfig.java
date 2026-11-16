package sk.therasuite.iam.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import sk.therasuite.iam.application.BootstrapProperties;
import sk.therasuite.iam.application.IamProperties;
import sk.therasuite.iam.domain.UserStatus;
import sk.therasuite.shared.AuthenticatedUser;

/**
 * Serverová relácia (cookie HttpOnly, Secure, SameSite=Strict), CSRF token v cookie + hlavičke
 * (SPA), všetko okrem health, prihlásenia a (v local/demo) Swaggeru vyžaduje prihlásenie.
 */
@Configuration
@EnableConfigurationProperties({IamProperties.class, BootstrapProperties.class})
class SecurityConfig {

  private static final Set<String> ALLOWED_WHEN_PASSWORD_CHANGE_REQUIRED =
      Set.of("POST /api/me/password", "POST /api/auth/logout");

  @Bean
  PasswordEncoder passwordEncoder() {
    return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
  }

  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityContextRepository contextRepository,
      UserAccountRepository users,
      @Value("${springdoc.swagger-ui.enabled:false}") boolean swagger)
      throws Exception {
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookieCustomizer(c -> c.secure(true).sameSite("Strict"));

    http.authorizeHttpRequests(
            a -> {
              a.requestMatchers("/actuator/health", "/actuator/health/**", "/api/auth/login")
                  .permitAll();
              if (swagger) {
                a.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll();
              }
              a.anyRequest().authenticated();
            })
        .csrf(c ->
            c.csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .securityContext(c -> c.securityContextRepository(contextRepository))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .logout(AbstractHttpConfigurer::disable)
        .exceptionHandling(e ->
            e.authenticationEntryPoint((req, res, ex) ->
                    writeError(res, 401, "UNAUTHENTICATED", "Vyžaduje sa prihlásenie."))
                .accessDeniedHandler((req, res, ex) ->
                    writeError(res, 403, "FORBIDDEN", "Na túto akciu nemáte oprávnenie.")))
        .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
        .addFilterAfter(new AccountStateFilter(users), AnonymousAuthenticationFilter.class);
    return http.build();
  }

  static void writeError(HttpServletResponse res, int status, String code, String detail)
      throws IOException {
    res.setStatus(status);
    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    res.setCharacterEncoding("UTF-8");
    res.getWriter()
        .write("{\"status\":%d,\"code\":\"%s\",\"detail\":\"%s\"}".formatted(status, code, detail));
  }

  /** Zabezpečí, že sa CSRF cookie vždy odošle (SPA ju číta a posiela v hlavičke X-XSRF-TOKEN). */
  static class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
      if (token != null) {
        token.getToken();
      }
      chain.doFilter(request, response);
    }
  }

  /**
   * Pri každej požiadavke prihláseného používateľa kontroluje aktuálny stav v databáze:
   * deaktivovaný účet stráca reláciu; kým je vynútená zmena hesla, povolené je len POST
   * /api/me/password a POST /api/auth/logout.
   */
  static class AccountStateFilter extends OncePerRequestFilter {
    private final UserAccountRepository users;

    AccountStateFilter(UserAccountRepository users) {
      this.users = users;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
        var flags = users.findFlags(user.id()).orElse(null);
        if (flags == null || flags.status() == UserStatus.DISABLED) {
          SecurityContextHolder.clearContext();
          HttpSession session = request.getSession(false);
          if (session != null) {
            session.invalidate();
          }
          writeError(response, 401, "UNAUTHENTICATED", "Vyžaduje sa prihlásenie.");
          return;
        }
        String call = request.getMethod() + " " + request.getRequestURI();
        if (flags.mustChangePassword()
            && !ALLOWED_WHEN_PASSWORD_CHANGE_REQUIRED.contains(call)
            && !HttpMethod.OPTIONS.matches(request.getMethod())) {
          writeError(response, 403, "PASSWORD_CHANGE_REQUIRED", "Najprv si musíte zmeniť heslo.");
          return;
        }
      }
      chain.doFilter(request, response);
    }
  }
}
