package sk.therasuite.shared;

import java.io.Serializable;

/** Prihlásený používateľ uložený v relácii (identifikátor a login, nič citlivé). */
public record AuthenticatedUser(long id, String username) implements Serializable {}
