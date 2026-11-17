package sk.therasuite.accommodation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sk.therasuite.TestUsers;
import sk.therasuite.TestcontainersConfig;

/**
 * Výkon celého plánu nad demo dátami (431 izieb × 31 dní). V CI sa nespúšťa; lokálne: {@code ./mvnw
 * test -Dtest.excludedGroups=none -Dgroups=performance -Dtest=AccommodationPlanPerformanceTest}
 */
@Tag("performance")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "demo"})
@Import(TestcontainersConfig.class)
class AccommodationPlanPerformanceTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Test
  void fullPlanOfDemoDataIsBelow300Ms() throws Exception {
    var user = TestUsers.as(jdbc, "veduca");
    String url = "/api/accommodation-plan?from=2025-01-01&to=2025-02-01";
    String warm = mvc.perform(get(url).with(user)).andReturn().getResponse().getContentAsString();
    assertThat(JsonPath.<java.util.List<Object>>read(warm, "$.rooms")).hasSize(431);

    long best = Long.MAX_VALUE;
    for (int i = 0; i < 5; i++) {
      long start = System.nanoTime();
      mvc.perform(get(url).with(user)).andReturn();
      best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
    }
    System.out.println("Plán 431 izieb × 31 dní: " + best + " ms");
    assertThat(best).isLessThan(300);
  }
}
