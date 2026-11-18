package sk.therasuite.audit.infrastructure;

import java.time.YearMonth;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pri štarte vytvorí mesačné partície audit tabuliek pre aktuálny a nasledujúce 2 mesiace (UTC).
 * Beží ako prvý, aby ostatné štartovacie zápisy (bootstrap, demo dáta) mali kam zapisovať audit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditPartitionInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(AuditPartitionInitializer.class);
  public static final int MONTHS_AHEAD = 2;
  private static final String[] TABLES = {"audit_event", "audit_change", "audit_subject_access"};

  private final JdbcTemplate jdbc;

  public AuditPartitionInitializer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void run(ApplicationArguments args) {
    ensurePartitions(YearMonth.now(ZoneOffset.UTC));
  }

  /**
   * Denne doplní partície, aby nevypršali pri dlhom behu bez reštartu. Cron sa dá zmeniť cez {@code
   * app.audit.partitions.cron}, hodnota {@code -} úlohu vypne. Chyba sa len zaloguje, ďalší beh to
   * dorobí.
   */
  @Scheduled(cron = "${app.audit.partitions.cron:0 15 1 * * *}", zone = "UTC")
  public void ensureUpcomingPartitions() {
    try {
      ensurePartitions(YearMonth.now(ZoneOffset.UTC));
    } catch (RuntimeException e) {
      log.error("Nepodarilo sa vytvoriť partície auditu, skúsi sa pri ďalšom behu.", e);
    }
  }

  /**
   * Vytvorí (ak ešte neexistujú) partície pre mesiac {@code first} a {@link #MONTHS_AHEAD} ďalších.
   */
  public void ensurePartitions(YearMonth first) {
    for (int i = 0; i <= MONTHS_AHEAD; i++) {
      YearMonth month = first.plusMonths(i);
      for (String table : TABLES) {
        jdbc.execute(
            "create table if not exists %s_y%04dm%02d partition of %s for values from ('%sT00:00:00Z') to ('%sT00:00:00Z')"
                .formatted(
                    table,
                    month.getYear(),
                    month.getMonthValue(),
                    table,
                    month.atDay(1),
                    month.plusMonths(1).atDay(1)));
      }
    }
  }
}
