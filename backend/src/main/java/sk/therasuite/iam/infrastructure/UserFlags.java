package sk.therasuite.iam.infrastructure;

import sk.therasuite.iam.domain.UserStatus;

/** Stav účtu a príznak vynútenej zmeny hesla (čítané pri každej požiadavke). */
public record UserFlags(UserStatus status, boolean mustChangePassword) {}
