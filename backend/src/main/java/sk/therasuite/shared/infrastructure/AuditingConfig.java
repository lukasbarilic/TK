package sk.therasuite.shared.infrastructure;

import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Plnenie created_by / updated_by. Kým nie je prihlásenie (P03), je to "system". */
@Configuration
@EnableJpaAuditing
class AuditingConfig {

  @Bean
  AuditorAware<String> auditorAware() {
    return () -> Optional.of("system");
  }
}
