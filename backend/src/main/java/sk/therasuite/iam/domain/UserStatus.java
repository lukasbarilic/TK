package sk.therasuite.iam.domain;

/** Stav účtu: aktívny, zamknutý po neúspešných prihláseniach, deaktivovaný (nemaže sa). */
public enum UserStatus {
  ACTIVE,
  LOCKED,
  DISABLED
}
