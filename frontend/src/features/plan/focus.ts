import { diffDays } from '../../shared/time';

/** Koľko dní po požadovanom dátume má byť v pláne vidieť. */
export const FOCUS_DAYS_AFTER = 7;

/**
 * Posun zobrazeného rozsahu (v dňoch) pre odkaz z karty `?date`: 0, ak rozsah z nastavení dátum
 * aj týždeň po ňom už obsahuje; inak sa rozsah posunie tak, aby bol dátum druhým zobrazeným dňom.
 * Dĺžka rozsahu ani uložený pohľad (plan.defaultView) sa nemenia, preto funguje pre ľubovoľný dátum.
 */
export function focusOffset(businessDate: string, date: string, daysBack: number, daysAhead: number): number {
  const diff = diffDays(businessDate, date);
  if (diff >= -daysBack && diff + FOCUS_DAYS_AFTER <= daysAhead) return 0;
  return diff - 1 + daysBack;
}
