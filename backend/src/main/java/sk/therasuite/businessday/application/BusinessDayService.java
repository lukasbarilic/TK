package sk.therasuite.businessday.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.businessday.domain.BusinessDay;
import sk.therasuite.businessday.domain.ClosingRule;
import sk.therasuite.businessday.domain.Finding;
import sk.therasuite.businessday.domain.Severity;
import sk.therasuite.businessday.infrastructure.BusinessDayQueries;
import sk.therasuite.businessday.infrastructure.BusinessDayQueries.RuleConfig;
import sk.therasuite.notification.application.LiveEvent;
import sk.therasuite.shared.AuditedOperation;
import sk.therasuite.shared.AuthenticatedUser;
import sk.therasuite.shared.application.ApiException;
import tools.jackson.databind.json.JsonMapper;

/** Obchodný deň recepcie a nočná uzávierka (HLD kap. 5.8); oprávnenia kontroluje controller. */
@Service
public class BusinessDayService {

  /** Zistenie so závažnosťou z nastavenia pravidla. */
  public record FindingView(
      String ruleCode, Severity severity, Long reservationId, String message) {}

  /** Výsledok kontroly rozdelený na blokujúce a informatívne zistenia. */
  public record Preview(
      long receptionId,
      LocalDate businessDate,
      boolean canClose,
      List<FindingView> blocking,
      List<FindingView> info) {}

  public record DayView(long receptionId, LocalDate businessDate, String status) {}

  private final BusinessDayQueries queries;
  private final List<ClosingRule> rules;
  private final BusinessDayProperties properties;
  private final JsonMapper json;
  private final ApplicationEventPublisher events;

  public BusinessDayService(
      BusinessDayQueries queries,
      List<ClosingRule> rules,
      BusinessDayProperties properties,
      JsonMapper json,
      ApplicationEventPublisher events) {
    this.queries = queries;
    this.rules = rules;
    this.properties = properties;
    this.json = json;
    this.events = events;
  }

  /**
   * Otvorí prvý deň recepciám, ktoré ešte žiadny nemajú: dnešný dátum v organization.timezone
   * (alebo app.business-day.initial). Vracia počet otvorených dní.
   */
  @Transactional
  public int openMissingDays() {
    List<Long> missing = queries.receptionsWithoutBusinessDay();
    LocalDate first =
        properties.initial() != null
            ? properties.initial()
            : LocalDate.now(ZoneId.of(properties.timezone()));
    missing.forEach(id -> queries.insertOpen(id, first));
    return missing.size();
  }

  @Transactional(readOnly = true)
  public DayView current(long receptionId) {
    BusinessDay day = open(receptionId);
    return new DayView(receptionId, day.businessDate(), day.status());
  }

  @Transactional(readOnly = true)
  public Preview preview(long receptionId) {
    return evaluate(open(receptionId));
  }

  /** Uzavrie deň a otvorí nasledujúci; pri blokujúcich zisteniach vráti 409. */
  @AuditedOperation(operation = "BUSINESS_DAY_CLOSE")
  @Transactional
  public DayView close(long receptionId) {
    BusinessDay day = open(receptionId);
    Preview preview = evaluate(day);
    if (!preview.canClose()) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "CLOSING_BLOCKED",
          "Uzávierku blokuje " + preview.blocking().size() + " zistení.");
    }
    long userId = currentUserId();
    queries.close(day.id(), userId, summaryJson(preview));
    LocalDate next = day.businessDate().plusDays(1);
    queries.insertOpen(receptionId, next);
    events.publishEvent(new LiveEvent.BusinessDayChanged(receptionId, next));
    return new DayView(receptionId, next, "OPEN");
  }

  private Preview evaluate(BusinessDay day) {
    List<FindingView> blocking = new ArrayList<>();
    List<FindingView> info = new ArrayList<>();
    for (ClosingRule rule : rules) {
      RuleConfig config = queries.ruleConfig(rule.code(), day.receptionId()).orElse(null);
      if (config == null || !config.enabled()) {
        continue; // vypnuté pravidlo sa nevyhodnocuje
      }
      Severity severity = Severity.valueOf(config.severity());
      for (Finding f : rule.evaluate(day.receptionId(), day.businessDate())) {
        FindingView view = new FindingView(f.ruleCode(), severity, f.reservationId(), f.message());
        (severity == Severity.BLOCKING ? blocking : info).add(view);
      }
    }
    return new Preview(day.receptionId(), day.businessDate(), blocking.isEmpty(), blocking, info);
  }

  private String summaryJson(Preview preview) {
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("blocking", preview.blocking());
    summary.put("info", preview.info());
    return json.writeValueAsString(summary);
  }

  private BusinessDay open(long receptionId) {
    if (!queries.receptionExists(receptionId)) {
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recepcia sa nenašla.");
    }
    return queries
        .findOpen(receptionId)
        .orElseThrow(() ->
            new ApiException(
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Recepcia nemá otvorený obchodný deň."));
  }

  private static long currentUserId() {
    var auth =
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
      return user.id();
    }
    throw new org.springframework.security.access.AccessDeniedException("Nie je prihlásený");
  }
}
