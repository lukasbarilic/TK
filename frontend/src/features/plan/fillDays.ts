/** Najviac dní v pláne naraz (ochrana výkonu mriežky a objemu načítaných dát). */
export const MAX_PLAN_DAYS = 92;
/** Načítaný rozsah sa zväčšuje po týždňoch, aby zmena šírky okna nespúšťala načítanie pri každom pixeli. */
export const FILL_STEP_DAYS = 7;
/** Rezerva na rámik a zvislý posuvník mriežky. */
export const GRID_CHROME_PX = 20;

export interface FillDays {
  /** Koľko dní zobraziť navyše za `daysAhead`, aby mriežka vyplnila šírku. */
  show: number;
  /** Koľko dní navyše načítať (`show` zaokrúhlené nahor na celé týždne). */
  fetch: number;
}

/**
 * Doplnenie dní podľa šírky mriežky: šírka dňa zostáva podľa zoomu, pribúda počet dní.
 * `baseDays` je rozsah z nastavení (daysBack + daysAhead + 1); ten sa nikdy nezmenšuje.
 */
export function fillDays(viewportWidth: number, dayWidth: number, leftWidth: number, baseDays: number): FillDays {
  if (viewportWidth <= 0 || dayWidth <= 0) return { show: 0, fetch: 0 };
  const fit = Math.floor((viewportWidth - leftWidth - GRID_CHROME_PX) / dayWidth);
  const cap = Math.max(0, MAX_PLAN_DAYS - baseDays);
  const show = Math.min(cap, Math.max(0, fit - baseDays));
  const fetch = Math.min(cap, Math.ceil(show / FILL_STEP_DAYS) * FILL_STEP_DAYS);
  return { show, fetch };
}
