export type ColorKey = 'RESERVED' | 'CHECKED_IN' | 'CHECKED_OUT' | 'HELD_BED' | 'ROOM_BLOCK';

export interface BarColors {
  fill: string;
  border: string;
  text: string;
}

/** Predvolené farby podľa docs/frontend/ui/README.md. */
export const DEFAULT_COLORS: Record<ColorKey, BarColors> = {
  RESERVED: { fill: '#CFE3F7', border: '#5B8FC9', text: '#13243A' },
  CHECKED_IN: { fill: '#1E5FA8', border: '#154A85', text: '#FFFFFF' },
  CHECKED_OUT: { fill: '#E3E8EE', border: '#9AA8B8', text: '#4A5D75' },
  HELD_BED: { fill: '#EAF3FC', border: '#5B8FC9', text: '#4A5D75' },
  ROOM_BLOCK: { fill: '#C8CED6', border: '#8A94A1', text: '#13243A' },
};

const HEX = /^#[0-9a-f]{6}$/i;

function shade(hex: string, factor: number): string {
  const n = parseInt(hex.slice(1), 16);
  const ch = (shift: number) => Math.round(((n >> shift) & 255) * factor);
  return `#${[16, 8, 0].map((s) => ch(s).toString(16).padStart(2, '0')).join('')}`;
}

function textOn(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  const lum = 0.299 * ((n >> 16) & 255) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255);
  return lum < 140 ? '#FFFFFF' : '#13243A';
}

/**
 * Farby pásikov z `plan.colors` (B06): objekt `{ RESERVED: '#RRGGBB', … }`. Neplatné alebo chýbajúce
 * hodnoty nahradia predvolené.
 */
export function resolveColors(raw: unknown): Record<ColorKey, BarColors> {
  const out = { ...DEFAULT_COLORS };
  if (typeof raw !== 'object' || raw === null) return out;
  for (const key of Object.keys(DEFAULT_COLORS) as ColorKey[]) {
    const v = (raw as Record<string, unknown>)[key];
    if (typeof v === 'string' && HEX.test(v)) out[key] = { fill: v, border: shade(v, 0.75), text: textOn(v) };
  }
  return out;
}

export function colorKeyOf(bar: { type: string; status?: string }): ColorKey {
  if (bar.type === 'HELD_BED') return 'HELD_BED';
  if (bar.type === 'ROOM_BLOCK') return 'ROOM_BLOCK';
  return bar.status === 'CHECKED_IN' || bar.status === 'CHECKED_OUT' ? bar.status : 'RESERVED';
}
