import type { DayReport } from './businessDayApi';

export const fmtDay = (day: string, lang: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString(lang, { weekday: 'short', day: 'numeric', month: 'numeric', year: 'numeric', timeZone: 'UTC' });

export const fmtShort = (day: string, lang: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString(lang, { day: 'numeric', month: 'numeric', timeZone: 'UTC' });

export const isWeekend = (day: string) => {
  const d = new Date(`${day}T00:00:00Z`).getUTCDay();
  return d === 0 || d === 6;
};

export const pct = (v: number | undefined) => `${Math.round(v ?? 0)} %`;

export type ForecastPoint = { date: string; percent: number };

export const forecastOf = (r: DayReport): ForecastPoint[] =>
  (r.forecast ?? []).filter((f) => f.date !== undefined).map((f) => ({ date: f.date as string, percent: f.percent ?? 0 }));

/** Indexy bodov, ktoré dostanú popis: prvý, najvyšší (prvý z rovnakých) a posledný. */
export const labelledIndexes = (points: ForecastPoint[]): Set<number> => {
  if (points.length === 0) return new Set();
  let maxIdx = 0;
  points.forEach((p, i) => { if (p.percent > points[maxIdx].percent) maxIdx = i; });
  return new Set([0, maxIdx, points.length - 1]);
};
