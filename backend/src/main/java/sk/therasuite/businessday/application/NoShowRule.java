package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import sk.therasuite.businessday.domain.ClosingRule;
import sk.therasuite.businessday.domain.Finding;
import sk.therasuite.businessday.infrastructure.BusinessDayQueries;

/** Karty RESERVED s príchodom najneskôr v obchodný deň (hosť nedorazil). */
@Component
public class NoShowRule implements ClosingRule {

  public static final String CODE = "NO_SHOW";

  private final BusinessDayQueries queries;

  public NoShowRule(BusinessDayQueries queries) {
    this.queries = queries;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public List<Finding> evaluate(long receptionId, LocalDate businessDate) {
    return queries.cardsByStatus("RESERVED", "arrival_date", businessDate).stream()
        .map(c -> new Finding(CODE, c.id(), "Karta " + c.number() + " nedorazila (no-show)."))
        .toList();
  }
}
