package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import sk.therasuite.businessday.domain.ClosingRule;
import sk.therasuite.businessday.domain.Finding;
import sk.therasuite.businessday.infrastructure.BusinessDayQueries;

/** Karty CHECKED_IN, ktoré v obchodný deň nemajú pobyt (hosť bez pridelenej izby / lôžka). */
@Component
public class CheckedInWithoutStayRule implements ClosingRule {

  public static final String CODE = "CHECKED_IN_WITHOUT_STAY";

  private final BusinessDayQueries queries;

  public CheckedInWithoutStayRule(BusinessDayQueries queries) {
    this.queries = queries;
  }

  @Override
  public String code() {
    return CODE;
  }

  @Override
  public List<Finding> evaluate(long receptionId, LocalDate businessDate) {
    return queries.checkedInWithoutStay(receptionId, businessDate).stream()
        .map(c -> new Finding(CODE, c.id(), "Karta " + c.number() + " je ubytovaná bez pobytu."))
        .toList();
  }
}
