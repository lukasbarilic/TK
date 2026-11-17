package sk.therasuite.shared;

import java.util.Collection;

/** Výsledok metódy s {@link AuditedRead}: dáta pre odpoveď a ID klientov, ktorých sa týkajú. */
public record AuditedReadResult<T>(T value, Collection<Long> clientIds) {}
