import type { DefaultView } from './types';

/** Stav plánu v pamäti relácie (nie v `plan.defaultView`): rozsah, filtre, zoom a pozícia rolovania; obnoví sa po návrate z karty. */
export interface PlanSession {
  filters: DefaultView;
  offset: number;
  scrollTop: number;
  scrollLeft: number;
}

const KEY = 'plan.session';

export function loadPlanSession(): PlanSession | undefined {
  try {
    const raw = sessionStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as PlanSession) : undefined;
  } catch {
    return undefined;
  }
}

export function savePlanSession(patch: Partial<PlanSession>, base: PlanSession): PlanSession {
  const next = { ...base, ...patch };
  try {
    sessionStorage.setItem(KEY, JSON.stringify(next));
  } catch {
    /* pamäť relácie nie je dostupná – plán sa len nezapamätá */
  }
  return next;
}

export const clearPlanSession = () => sessionStorage.removeItem(KEY);
