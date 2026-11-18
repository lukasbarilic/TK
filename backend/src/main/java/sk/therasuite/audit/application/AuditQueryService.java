package sk.therasuite.audit.application;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sk.therasuite.audit.application.AuditViews.AccessView;
import sk.therasuite.audit.application.AuditViews.ChangeView;
import sk.therasuite.audit.application.AuditViews.EventDetail;
import sk.therasuite.audit.application.AuditViews.EventSummary;
import sk.therasuite.audit.application.AuditViews.PageView;
import sk.therasuite.audit.infrastructure.AuditJson;
import sk.therasuite.audit.infrastructure.AuditRepository;
import sk.therasuite.audit.infrastructure.AuditRepository.EventRow;
import sk.therasuite.audit.infrastructure.AuditRepository.Filter;
import sk.therasuite.shared.application.ApiException;

/** Čítanie audit záznamov (oprávnenie AUDIT_VIEW kontroluje controller). */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

  public static final int MAX_PAGE_SIZE = 100;

  private final AuditRepository repository;
  private final AuditJson json;

  public AuditQueryService(AuditRepository repository, AuditJson json) {
    this.repository = repository;
    this.json = json;
  }

  public PageView<EventSummary> search(Filter filter, int page, int size) {
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    int safePage = Math.max(0, page);
    List<EventSummary> content =
        repository.search(filter, safeSize, safePage * safeSize).stream()
            .map(AuditQueryService::summary)
            .toList();
    return new PageView<>(content, safePage, safeSize, repository.count(filter));
  }

  public PageView<AccessView> clientAccesses(
      long clientId, Instant from, Instant to, int page, int size) {
    if (from != null && to != null && from.isAfter(to)) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Parameter 'from' musí byť pred 'to'.");
    }
    int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
    int safePage = Math.max(0, page);
    List<AccessView> content =
        repository.accessesOf(clientId, from, to, safeSize, safePage * safeSize).stream()
            .map(a ->
                new AccessView(
                    a.eventId(),
                    a.occurredAt(),
                    a.userId(),
                    a.username(),
                    a.roles(),
                    a.operation(),
                    a.dataScope(),
                    a.source()))
            .toList();
    return new PageView<>(
        content, safePage, safeSize, repository.countAccesses(clientId, from, to));
  }

  public EventDetail detail(long id) {
    EventRow row =
        repository
            .findEvent(id)
            .orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Audit záznam neexistuje."));
    List<ChangeView> changes =
        repository.changesOf(id).stream()
            .map(c ->
                new ChangeView(
                    c.id(),
                    c.entityType(),
                    c.entityId(),
                    c.changeType(),
                    json.parse(c.beforeState()),
                    json.parse(c.afterState()),
                    json.parse(c.changedFields())))
            .toList();
    return new EventDetail(
        summary(row),
        json.parse(row.requestPayload()),
        json.parse(row.confirmedWarnings()),
        changes);
  }

  private static EventSummary summary(EventRow r) {
    return new EventSummary(
        r.id(),
        r.eventUid(),
        r.eventType(),
        r.occurredAt(),
        r.userId(),
        r.username(),
        r.roles(),
        r.operation(),
        r.useCase(),
        r.targetType(),
        r.targetId(),
        r.outcome(),
        r.errorCode(),
        r.requestId(),
        r.clientIp(),
        r.userAgent());
  }
}
