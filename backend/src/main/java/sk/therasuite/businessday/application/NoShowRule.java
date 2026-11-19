package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import sk.therasuite.businessday.domain.ClosingRule;
import sk.therasuite.businessday.domain.Finding;
import sk.therasuite.businessday.infrastructure.BusinessDayReportQueries;

/** Karty RESERVED s príchodom najneskôr v obchodný deň (hosť nedorazil). */
@Component
public class NoShowRule implements ClosingRule {

  public static final String CODE = "NO_SHOW";

  private final BusinessDayReportQueries queries;

  public NoShowRule(BusinessDayReportQueries queries) {
    this.queries = queries;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public List<Finding> evaluate(long receptionId, LocalDate businessDate) {
    return queries.noShowCandidates(receptionId, businessDate).stream()
        .map(c -> new Finding(CODE, c.id(), "Karta " + c.number() + " nedorazila (no-show)."))
        .toList();
  }
}
