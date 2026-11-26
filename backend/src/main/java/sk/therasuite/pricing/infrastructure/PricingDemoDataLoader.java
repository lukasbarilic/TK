package sk.therasuite.pricing.infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Načíta fiktívny demo cenník (classpath:demo/pricing.sql). Beží len v profile {@code demo}, po
 * načítaní katalógu a len ak ešte neexistuje žiadny cenník.
 */
@Component
@Profile("demo")
@Order(20)
public class PricingDemoDataLoader implements ApplicationRunner {

  private final JdbcTemplate jdbc;

  public PricingDemoDataLoader(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    Long categories = jdbc.queryForObject("select count(*) from room_category", Long.class);
    Long lists = jdbc.queryForObject("select count(*) from price_list", Long.class);
    if (categories == null || categories == 0 || (lists != null && lists > 0)) {
      return;
    }
    jdbc.execute(
        (java.sql.Connection con) -> {
          ScriptUtils.executeSqlScript(con, new ClassPathResource("demo/pricing.sql"));
          return null;
        });
  }
}
