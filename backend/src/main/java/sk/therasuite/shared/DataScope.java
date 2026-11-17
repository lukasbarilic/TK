package sk.therasuite.shared;

/** Rozsah osobných údajov klienta v odpovedi (HLD kap. 7.2). */
public enum DataScope {
  /** Len meno (napr. skrátené meno na pásiku, zoznam kariet). */
  NAME,
  /** Meno a kontaktné údaje (napr. detail rezervačnej karty). */
  CONTACT,
  /** Všetky údaje klienta. */
  FULL
}
