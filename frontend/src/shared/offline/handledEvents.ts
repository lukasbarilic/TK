/**
 * Už spracované živé udalosti podľa `auditEventId`. Vlastná zmena (odpoveď zápisu nesie `auditEventId`) sa
 * zaznačí hneď, takže rovnaká udalosť `plan-changed` zo servera sa nespracuje druhýkrát (DLD kap. 9.3).
 */
const MAX = 500;
const handled = new Set<number>();

export function markEventHandled(auditEventId: number): void {
  handled.add(auditEventId);
  if (handled.size > MAX) handled.delete(handled.values().next().value as number);
}

export function wasEventHandled(auditEventId: number): boolean {
  return handled.has(auditEventId);
}

/** Len pre testy. */
export function resetHandledEvents(): void {
  handled.clear();
}
