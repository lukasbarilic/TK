// Kalendárne dni sú stringy 'YYYY-MM-DD'. Počíta sa v UTC, aby prechod na
// letný/zimný čas nikdy neposunul deň (žiadne lokálne Date).

const ISO_DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const MS_PER_DAY = 86_400_000;

function toUtc(day: string): number {
  const m = ISO_DAY.exec(day);
  if (!m) throw new Error(`Neplatný dátum: ${day}`);
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const t = Date.UTC(y, mo - 1, d);
  const check = new Date(t);
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d) {
    throw new Error(`Neplatný dátum: ${day}`);
  }
  return t;
}

function fromUtc(t: number): string {
  return new Date(t).toISOString().slice(0, 10);
}

export function addDays(day: string, n: number): string {
  return fromUtc(toUtc(day) + n * MS_PER_DAY);
}

/** Počet dní od `from` do `to` (kladné, ak `to` je neskôr). */
export function diffDays(from: string, to: string): number {
  return Math.round((toUtc(to) - toUtc(from)) / MS_PER_DAY);
}

/** Dni od `from` po `to` vrátane oboch koncov; prázdne pole, ak `to` < `from`. */
export function listDays(from: string, to: string): string[] {
  const n = diffDays(from, to);
  return Array.from({ length: Math.max(0, n + 1) }, (_, i) => addDays(from, i));
}

const WEEKDAYS = ['ne', 'po', 'ut', 'st', 'št', 'pi', 'so'];

/** Napr. '2026-11-17' → 'ut 17. 11.' */
export function formatDayShort(day: string): string {
  const t = new Date(toUtc(day));
  return `${WEEKDAYS[t.getUTCDay()]} ${t.getUTCDate()}. ${t.getUTCMonth() + 1}.`;
}
