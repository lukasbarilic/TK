package sk.therasuite.accommodation.domain;

/** Kódy chýb a upozornení kontroly obsadenosti (HLD kap. 5.2, 6.3). */
public enum OccupancyCode {
  BED_UNAVAILABLE(false),
  EXTRA_BED_AGE(false),
  BED_OCCUPIED(false),
  ROOM_BLOCKED(false),
  ROOM_NOT_EMPTY(false),
  PAST_CHANGE(false),
  OUTSIDE_RESERVATION(false),
  MISSING_PERMISSION(false),
  MAX_NIGHTS_EXCEEDED(false),
  CATEGORY_MISMATCH(false),
  RELEASES_HELD_BED(true),
  STAY_LOCKED(true),
  PARTY_MEMBER_MOVED(true);

  private final boolean warning;

  OccupancyCode(boolean warning) {
    this.warning = warning;
  }

  /** {@code true} = upozornenie na potvrdenie, inak chyba. */
  public boolean isWarning() {
    return warning;
  }
}
