package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import sk.therasuite.businessday.domain.ClosingRule;
import sk.therasuite.businessday.domain.Finding;
import sk.therasuite.businessday.infrastructure.BusinessDayQueries;

/** Karty CHECKED_IN s odchodom najneskôr v obchodný deň. */
@Component
public class DeparturesNotCheckedOutRule implements ClosingRule {

  public static final String CODE = "DEPARTURES_NOT_CHECKED_OUT";

  private final BusinessDayQueries queries;

  public DeparturesNotCheckedOutRule(BusinessDayQueries queries) {
    this.queries = queries;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public List<Finding> evaluate(long receptionId, LocalDate businessDate) {
    return queries.cardsByStatus("CHECKED_IN", "departure_date", businessDate).stream()
        .map(c ->
            new Finding(CODE, c.id(), "Karta " + c.number() + " mala odísť a nie je odhlásená."))
        .toList();
  }
}
