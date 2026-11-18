package sk.therasuite.iam.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Čítanie a zápis rolí, oprávnení a pridelení (user_role) cez SQL. */
@Repository
public class IamQueries {

  /** Jedno platné oprávnenie používateľa cez jedno pridelenie roly. */
  public record Grant(String code, Integer limit, Long centreId, Long receptionId) {}

  /** Pridelenie roly používateľovi. */
  public record Assignment(
      String roleCode,
      String roleName,
      Long centreId,
      Long receptionId,
      LocalDate validFrom,
      LocalDate validTo) {

    public boolean validOn(LocalDate day) {
      return (validFrom == null || !validFrom.isAfter(day))
          && (validTo == null || !validTo.isBefore(day));
    }
  }

  /** Oprávnenie z číselníka. */
  public record PermissionRow(String code, String module, String description, boolean hasLimit) {}

  /** Oprávnenie pridelené roli, s prípadným limitom. */
  public record RolePermissionRow(String code, String description, Integer limit) {}

  /** Rola bez oprávnení. */
  public record RoleRow(
      long id, String code, String name, String description, boolean systemRole) {}

  private final JdbcTemplate jdbc;

  public IamQueries(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Oprávnenia aktívneho používateľa z pridelení platných v daný deň. */
  public List<Grant> grants(long userId, LocalDate day) {
    return jdbc.query(
        """
        select rp.permission_code, rp.limit_value, ur.centre_id, ur.reception_id
        from user_account u
        join user_role ur on ur.user_id = u.id
        join role_permission rp on rp.role_id = ur.role_id
        where u.id = ? and u.status = 'ACTIVE'
          and (ur.valid_from is null or ur.valid_from <= ?)
          and (ur.valid_to is null or ur.valid_to >= ?)
        """,
        (rs, i) ->
            new Grant(
                rs.getString(1),
                (Integer) rs.getObject(2),
                (Long) rs.getObject(3),
                (Long) rs.getObject(4)),
        userId,
        Date.valueOf(day),
        Date.valueOf(day));
  }

  public List<Long> centreIdsOfReception(long receptionId) {
    return jdbc.queryForList(
        "select distinct centre_id from building where reception_id = ?", Long.class, receptionId);
  }

  public List<Assignment> assignments(long userId) {
    return jdbc.query(
        """
        select r.code, r.name, ur.centre_id, ur.reception_id, ur.valid_from, ur.valid_to
        from user_role ur join role r on r.id = ur.role_id
        where ur.user_id = ? order by r.code, ur.id
        """,
        (rs, i) ->
            new Assignment(
                rs.getString(1),
                rs.getString(2),
                (Long) rs.getObject(3),
                (Long) rs.getObject(4),
                rs.getObject(5, LocalDate.class),
                rs.getObject(6, LocalDate.class)),
        userId);
  }

  public List<PermissionRow> allPermissions() {
    return jdbc.query(
        "select code, module, description, has_limit from permission order by module, code",
        (rs, i) ->
            new PermissionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)));
  }

  public List<RoleRow> allRoles() {
    return jdbc.query(
        "select id, code, name, description, system_role from role order by code",
        (rs, i) ->
            new RoleRow(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getBoolean(5)));
  }

  /** Oprávnenia všetkých rolí, kľúč je id roly. */
  public java.util.Map<Long, List<RolePermissionRow>> rolePermissions() {
    java.util.Map<Long, List<RolePermissionRow>> result = new java.util.HashMap<>();
    jdbc.query(
        """
        select rp.role_id, rp.permission_code, p.description, rp.limit_value
        from role_permission rp join permission p on p.code = rp.permission_code
        order by rp.role_id, rp.permission_code
        """,
        rs -> {
          result
              .computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>())
              .add(
                  new RolePermissionRow(
                      rs.getString(2), rs.getString(3), (Integer) rs.getObject(4)));
        });
    return result;
  }

  public Optional<Long> roleId(String code) {
    return jdbc.queryForList("select id from role where code = ?", Long.class, code).stream()
        .findFirst();
  }

  public boolean centreExists(long id) {
    return exists("centre", id);
  }

  public boolean receptionExists(long id) {
    return exists("reception", id);
  }

  public Optional<Long> receptionIdByCode(String code) {
    return jdbc.queryForList("select id from reception where code = ?", Long.class, code).stream()
        .findFirst();
  }

  private boolean exists(String table, long id) {
    Integer n =
        jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
    return n != null && n > 0;
  }

  public void clearAssignments(long userId) {
    jdbc.update("delete from user_role where user_id = ?", userId);
  }

  public void addAssignment(
      long userId,
      long roleId,
      Long centreId,
      Long receptionId,
      LocalDate validFrom,
      LocalDate validTo) {
    jdbc.update(
        "insert into user_role (user_id, role_id, centre_id, reception_id, valid_from, valid_to)"
            + " values (?, ?, ?, ?, ?, ?)",
        userId,
        roleId,
        centreId,
        receptionId,
        validFrom == null ? null : Date.valueOf(validFrom),
        validTo == null ? null : Date.valueOf(validTo));
  }
}
