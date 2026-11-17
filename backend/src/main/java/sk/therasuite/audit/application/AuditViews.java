package sk.therasuite.audit.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Tvar audit záznamov v odpovediach API. */
public final class AuditViews {

  private AuditViews() {}

  public record EventSummary(
      long id,
      UUID eventUid,
      String eventType,
      Instant occurredAt,
      Long userId,
      String username,
      String roles,
      String operation,
      String useCase,
      String targetType,
      Long targetId,
      String outcome,
      String errorCode,
      String requestId,
      String clientIp,
      String userAgent) {}

  public record ChangeView(
      long id,
      String entityType,
      Long entityId,
      String changeType,
      Object beforeState,
      Object afterState,
      Object changedFields) {}

  public record EventDetail(
      EventSummary event,
      Object requestPayload,
      Object confirmedWarnings,
      List<ChangeView> changes) {}

  public record PageView<T>(List<T> content, int page, int size, long totalElements) {}
}
