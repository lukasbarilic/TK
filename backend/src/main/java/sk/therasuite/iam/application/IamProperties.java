package sk.therasuite.iam.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Nastavenia zabezpečenia (HLD kap. 9): {@code security.login.*}, {@code security.session.*}. */
@ConfigurationProperties("security")
public record IamProperties(@DefaultValue Login login, @DefaultValue Session session) {

  public record Login(
      @DefaultValue("5") int maxFailedAttempts, @DefaultValue("PT15M") Duration lockDuration) {}

  public record Session(@DefaultValue("PT30M") Duration idleTimeout) {}
}
