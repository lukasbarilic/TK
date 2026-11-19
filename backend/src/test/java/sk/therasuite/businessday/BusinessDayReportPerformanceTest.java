package sk.therasuite.businessday;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import sk.therasuite.TestcontainersConfig;
import sk.therasuite.businessday.application.BusinessDayReportService;

/** Report dňa nad demo dátami (všetky recepcie) musí byť rýchly: do 500 ms na recepciu. */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@Import(TestcontainersConfig.class)
class BusinessDayReportPerformanceTest {

  @Autowired JdbcTemplate jdbc;
  @Autowired BusinessDayReportService reports;

  @Test
  void reportOverDemoDataIsBelow500Ms() {
    List<Long> receptions = jdbc.queryForList("select id from reception order by id", Long.class);
    assertThat(receptions).isNotEmpty();
    LocalDate day = LocalDate.of(2025, 1, 15);
    receptions.forEach(r -> reports.build(r, day)); // zahriatie
    long worst = 0;
    for (long reception : receptions) {
      long start = System.nanoTime();
      var report = reports.build(reception, day);
      worst = Math.max(worst, (System.nanoTime() - start) / 1_000_000);
      assertThat(report.forecast()).hasSize(14);
    }
    System.out.println("Report dňa nad demo dátami, najhoršia recepcia: " + worst + " ms");
    assertThat(worst).isLessThan(500);
  }
}
