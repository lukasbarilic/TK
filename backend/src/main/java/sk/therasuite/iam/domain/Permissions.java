package sk.therasuite.iam.domain;

/** Kódy oprávnení (HLD kap. 8.3). Kód kontroluje oprávnenia, nikdy názvy rolí. */
public final class Permissions {

  public static final String PLAN_VIEW = "PLAN_VIEW";
  public static final String STAY_CREATE = "STAY_CREATE";
  public static final String STAY_BACKDATE = "STAY_BACKDATE";
  public static final String STAY_MOVE = "STAY_MOVE";
  public static final String STAY_MOVE_LOCKED = "STAY_MOVE_LOCKED";
  public static final String STAY_OTHER_CATEGORY = "STAY_OTHER_CATEGORY";
  public static final String USER_ADMIN = "USER_ADMIN";
  public static final String AUDIT_VIEW = "AUDIT_VIEW";
  public static final String RESERVATION_VIEW = "RESERVATION_VIEW";
  public static final String RESERVATION_EDIT = "RESERVATION_EDIT";
  public static final String DOCTOR_ASSIGN = "DOCTOR_ASSIGN";
  public static final String RESERVATION_STATUS = "RESERVATION_STATUS";
  public static final String RESERVATION_STATUS_FIX = "RESERVATION_STATUS_FIX";
  public static final String BUSINESS_DAY_CLOSE = "BUSINESS_DAY_CLOSE";
  public static final String REPORT_VIEW = "REPORT_VIEW";
  public static final String GUEST_LOCATION_VIEW = "GUEST_LOCATION_VIEW";
  public static final String GUEST_LOCATION_VIEW_ALL = "GUEST_LOCATION_VIEW_ALL";
  public static final String GUEST_LOCATION_EXPORT = "GUEST_LOCATION_EXPORT";

  /** Najvyšší limit (365 nocí = bez obmedzenia). */
  public static final int MAX_LIMIT = 365;

  private Permissions() {}
}
