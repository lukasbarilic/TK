package sk.therasuite.audit.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Zápis a čítanie audit tabuliek cez SQL (audit tabuľky nie sú JPA entity). */
@Repository
public class AuditRepository {

  /** Údaje nového audit záznamu. */
  public record NewEvent(
      String eventType,
      Long userId,
      String username,
      String roles,
      String operation,
      String useCase,
      String outcome,
      String errorCode,
      String requestPayloadJson,
      String requestId,
      String clientIp,
      String userAgent) {}

  /** Riadok audit_event (JSON stĺpce ako text). */
  public record EventRow(
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
      String requestPayload,
      String confirmedWarnings,
      String requestId,
      String clientIp,
      String userAgent) {}

  /** Riadok audit_change (JSON stĺpce ako text). */
  public record ChangeRow(
      long id,
      String entityType,
      Long entityId,
      String changeType,
      String beforeState,
      String afterState,
      String changedFields) {}

  /** Filter vyhľadávania; null znamená „bez obmedzenia“. */
  public record Filter(
      String type,
      String entityType,
      Long entityId,
      Long userId,
      Instant from,
      Instant to,
      String operation) {}

  /** Prístup k údajom klienta: kto a kedy ich čítal. */
  public record AccessRow(
      long eventId,
      Instant occurredAt,
      Long userId,
      String username,
      String roles,
      String operation,
      String dataScope) {}

  private static final String EVENT_COLUMNS =
      "e.id, e.event_uid, e.event_type, e.occurred_at, e.user_id, e.username, e.roles,"
          + " e.operation, e.use_case, e.target_type, e.target_id, e.outcome, e.error_code,"
          + " e.request_payload::text, e.confirmed_warnings::text, e.request_id, e.client_ip,"
          + " e.user_agent";

  private static final RowMapper<EventRow> EVENT_MAPPER =
      (rs, i) ->
          new EventRow(
              rs.getLong(1),
              rs.getObject(2, UUID.class),
              rs.getString(3),
              rs.getObject(4, OffsetDateTime.class).toInstant(),
              (Long) rs.getObject(5),
              rs.getString(6),
              rs.getString(7),
              rs.getString(8),
              rs.getString(9),
              rs.getString(10),
              (Long) rs.getObject(11),
              rs.getString(12),
              rs.getString(13),
              rs.getString(14),
              rs.getString(15),
              rs.getString(16),
              rs.getString(17),
              rs.getString(18));

  private final JdbcTemplate jdbc;

  public AuditRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Vloží audit_event v aktuálnej transakcii a vráti jeho identifikátor a čas. */
  public AuditContext.Current insertEvent(NewEvent e) {
    return jdbc.queryForObject(
        """
        insert into audit_event (event_type, user_id, username, roles, operation, use_case, outcome,
                                 error_code, request_payload, request_id, client_ip, user_agent)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
        returning id, occurred_at
        """,
        (rs, i) ->
            new AuditContext.Current(
                rs.getLong(1), rs.getObject(2, OffsetDateTime.class).toInstant()),
        e.eventType(),
        e.userId(),
        e.username(),
        e.roles(),
        e.operation(),
        e.useCase(),
        e.outcome(),
        e.errorCode(),
        e.requestPayloadJson(),
        e.requestId(),
        e.clientIp(),
        e.userAgent());
  }

  public void insertChange(
      AuditContext.Current event,
      String entityType,
      Long entityId,
      String changeType,
      String beforeJson,
      String afterJson,
      String changedFieldsJson) {
    jdbc.update(
        """
        insert into audit_change (audit_event_id, occurred_at, entity_type, entity_id, change_type,
                                  before_state, after_state, changed_fields)
        values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb)
        """,
        event.eventId(),
        OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC),
        entityType,
        entityId,
        changeType,
        beforeJson,
        afterJson,
        changedFieldsJson);
  }

  /** Jedným hromadným INSERT zapíše všetkých klientov čítania do audit_subject_access. */
  public void insertSubjectAccess(
      AuditContext.Current event, java.util.Collection<Long> clientIds, String dataScope) {
    if (clientIds.isEmpty()) {
      return;
    }
    Long[] ids = clientIds.stream().distinct().toArray(Long[]::new);
    jdbc.update(con -> {
      var ps =
          con.prepareStatement(
              "insert into audit_subject_access (audit_event_id, occurred_at, client_id,"
                  + " data_scope) select ?, ?, c, ? from unnest(?) as c");
      ps.setLong(1, event.eventId());
      ps.setObject(2, OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
      ps.setString(3, dataScope);
      ps.setArray(4, con.createArrayOf("bigint", ids));
      return ps;
    });
  }

  public List<AccessRow> accessesOf(
      long clientId, Instant from, Instant to, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = accessWhere(clientId, from, to, args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "select e.id, e.occurred_at, e.user_id, e.username, e.roles, e.operation, a.data_scope"
            + " from audit_subject_access a join audit_event e"
            + " on e.id = a.audit_event_id and e.occurred_at = a.occurred_at"
            + where
            + " order by e.occurred_at desc, e.id desc limit ? offset ?",
        (rs, i) ->
            new AccessRow(
                rs.getLong(1),
                rs.getObject(2, OffsetDateTime.class).toInstant(),
                (Long) rs.getObject(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)),
        args.toArray());
  }

  public long countAccesses(long clientId, Instant from, Instant to) {
    List<Object> args = new ArrayList<>();
    Long n =
        jdbc.queryForObject(
            "select count(*) from audit_subject_access a" + accessWhere(clientId, from, to, args),
            Long.class,
            args.toArray());
    return n == null ? 0 : n;
  }

  private static String accessWhere(long clientId, Instant from, Instant to, List<Object> args) {
    StringBuilder sql = new StringBuilder(" where a.client_id = ?");
    args.add(clientId);
    if (from != null) {
      sql.append(" and a.occurred_at >= ?");
      args.add(Timestamp.from(from));
    }
    if (to != null) {
      sql.append(" and a.occurred_at <= ?");
      args.add(Timestamp.from(to));
    }
    return sql.toString();
  }

  public Optional<Long> userIdByUsername(String username) {
    return jdbc
        .queryForList("select id from user_account where username = ?", Long.class, username)
        .stream()
        .findFirst();
  }

  /** Snímka kódov rolí používateľa (zoradené, oddelené čiarkou). */
  public String rolesOf(long userId) {
    return jdbc.queryForObject(
        """
        select string_agg(distinct r.code, ',' order by r.code)
        from user_role ur join role r on r.id = ur.role_id where ur.user_id = ?
        """,
        String.class,
        userId);
  }

  public List<EventRow> search(Filter f, int limit, int offset) {
    List<Object> args = new ArrayList<>();
    String where = where(f, args);
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        "select "
            + EVENT_COLUMNS
            + " from audit_event e"
            + where
            + " order by e.occurred_at desc, e.id desc limit ? offset ?",
        EVENT_MAPPER,
        args.toArray());
  }

  public long count(Filter f) {
    List<Object> args = new ArrayList<>();
    Long n =
        jdbc.queryForObject(
            "select count(*) from audit_event e" + where(f, args), Long.class, args.toArray());
    return n == null ? 0 : n;
  }

  public Optional<EventRow> findEvent(long id) {
    return jdbc
        .query("select " + EVENT_COLUMNS + " from audit_event e where e.id = ?", EVENT_MAPPER, id)
        .stream()
        .findFirst();
  }

  public List<ChangeRow> changesOf(long eventId) {
    return jdbc.query(
        """
        select id, entity_type, entity_id, change_type, before_state::text, after_state::text,
               changed_fields::text
        from audit_change where audit_event_id = ? order by id
        """,
        (rs, i) ->
            new ChangeRow(
                rs.getLong(1),
                rs.getString(2),
                (Long) rs.getObject(3),
                rs.getString(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)),
        eventId);
  }

  private static String where(Filter f, List<Object> args) {
    StringBuilder sql = new StringBuilder(" where 1 = 1");
    if (f.type() != null) {
      sql.append(" and e.event_type = ?");
      args.add(f.type());
    }
    if (f.userId() != null) {
      sql.append(" and e.user_id = ?");
      args.add(f.userId());
    }
    if (f.operation() != null) {
      sql.append(" and e.operation = ?");
      args.add(f.operation());
    }
    if (f.from() != null) {
      sql.append(" and e.occurred_at >= ?");
      args.add(Timestamp.from(f.from()));
    }
    if (f.to() != null) {
      sql.append(" and e.occurred_at <= ?");
      args.add(Timestamp.from(f.to()));
    }
    if (f.entityType() != null || f.entityId() != null) {
      sql.append(
          " and exists (select 1 from audit_change c where c.audit_event_id = e.id"
              + " and c.occurred_at = e.occurred_at");
      if (f.entityType() != null) {
        sql.append(" and c.entity_type = ?");
        args.add(f.entityType());
      }
      if (f.entityId() != null) {
        sql.append(" and c.entity_id = ?");
        args.add(f.entityId());
      }
      sql.append(")");
    }
    return sql.toString();
  }
}
