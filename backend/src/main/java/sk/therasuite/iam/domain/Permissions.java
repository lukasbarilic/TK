package sk.therasuite.iam.domain;

/** Kódy oprávnení (HLD kap. 8.3). Kód kontroluje oprávnenia, nikdy názvy rolí. */
public final class Permissions {

  public static final String PLAN_VIEW = "PLAN_VIEW";
  public static final String STAY_CREATE = "STAY_CREATE";
  public static final String USER_ADMIN = "USER_ADMIN";
  public static final String AUDIT_VIEW = "AUDIT_VIEW";

  /** Najvyšší limit (365 nocí = bez obmedzenia). */
  public static final int MAX_LIMIT = 365;

  private Permissions() {}
}
