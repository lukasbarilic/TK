/** Rozparsuje `major.minor.patch` (voliteľne s `-predvydanie` alebo `+build`); neplatný reťazec dá `null`. */
export function parseVersion(v: string): { core: [number, number, number]; pre: string[] } | null {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(v.trim());
  if (!m) return null;
  return { core: [Number(m[1]), Number(m[2]), Number(m[3])], pre: m[4] ? m[4].split('.') : [] };
}

function comparePre(a: string[], b: string[]): number {
  // verzia bez predvydania je vyššia ako s predvydaním
  if (a.length === 0 || b.length === 0) return a.length === b.length ? 0 : a.length === 0 ? 1 : -1;
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    const x = a[i];
    const y = b[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    const nx = /^\d+$/.test(x);
    const ny = /^\d+$/.test(y);
    if (nx && ny) {
      if (Number(x) !== Number(y)) return Number(x) < Number(y) ? -1 : 1;
    } else if (nx !== ny) {
      return nx ? -1 : 1;
    } else if (x !== y) {
      return x < y ? -1 : 1;
    }
  }
  return 0;
}

/** Porovná dve verzie podľa semver: záporné, 0, kladné. Neplatná verzia vyhodí chybu. */
export function compareVersions(a: string, b: string): number {
  const pa = parseVersion(a);
  const pb = parseVersion(b);
  if (!pa || !pb) throw new Error(`Neplatná verzia: ${pa ? b : a}`);
  for (let i = 0; i < 3; i++) {
    if (pa.core[i] !== pb.core[i]) return pa.core[i] < pb.core[i] ? -1 : 1;
  }
  return comparePre(pa.pre, pb.pre);
}

/** Je `current` nižšia ako `min`? Neplatná alebo chýbajúca hodnota neblokuje. */
export function isBelowMinimum(current: string, min: string | undefined): boolean {
  if (!min || !parseVersion(current) || !parseVersion(min)) return false;
  return compareVersions(current, min) < 0;
}
