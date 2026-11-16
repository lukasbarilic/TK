package sk.therasuite.shared.infrastructure;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import sk.therasuite.shared.AuthenticatedUser;

/** Plnenie created_by / updated_by prihláseným používateľom (mimo požiadavky ostáva NULL). */
@Configuration
@EnableJpaAuditing
class AuditingConfig {

  @Bean
  AuditorAware<Long> auditorAware() {
    return () -> {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
        return Optional.of(user.id());
      }
      return Optional.empty();
    };
  }
}
