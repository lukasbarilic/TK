package sk.therasuite.businessday.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Jedna kontrola nočnej uzávierky (HLD kap. 5.8). Zapnutie a závažnosť sú v tabuľke closing_rule.
 */
public interface ClosingRule {

  /** Kód pravidla zhodný s closing_rule.code. */
  String code();

  /** Zistenia v budovách danej recepcie k danému obchodnému dňu. */
  List<Finding> evaluate(long receptionId, LocalDate businessDate);
}
