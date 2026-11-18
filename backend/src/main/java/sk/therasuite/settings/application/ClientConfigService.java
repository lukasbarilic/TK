package sk.therasuite.settings.application;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Konfigurácia desktopového klienta: podporovaná verzia a nastavenia offline režimu. */
@Service
public class ClientConfigService {

  /** Nastavenia offline režimu; {@code maxAgeDays == null} = bez obmedzenia veku dát. */
  public record Offline(
      int planDaysBack, int planDaysAhead, int syncIntervalSec, Integer maxAgeDays) {}

  public record ClientConfig(
      int apiVersion,
      String minClientVersion,
      Instant serverTime,
      int idleTimeoutSec,
      Offline offline) {}

  private final int apiVersion;
  private final String minClientVersion;
  private final int idleTimeoutSec;
  private final Offline offline;

  public ClientConfigService(
      @Value("${app.client.api-version}") int apiVersion,
      @Value("${app.client.min-client-version}") String minClientVersion,
      @Value("${app.client.idle-timeout-sec}") int idleTimeoutSec,
      @Value("${app.client.offline.plan-days-back}") int planDaysBack,
      @Value("${app.client.offline.plan-days-ahead}") int planDaysAhead,
      @Value("${app.client.offline.sync-interval-sec}") int syncIntervalSec,
      @Value("${app.client.offline.max-age-days:#{null}}") Integer maxAgeDays) {
    this.apiVersion = apiVersion;
    this.minClientVersion = minClientVersion;
    this.idleTimeoutSec = idleTimeoutSec;
    this.offline = new Offline(planDaysBack, planDaysAhead, syncIntervalSec, maxAgeDays);
  }

  public ClientConfig config() {
    return new ClientConfig(apiVersion, minClientVersion, Instant.now(), idleTimeoutSec, offline);
  }
}
