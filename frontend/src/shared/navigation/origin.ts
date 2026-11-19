/** Pôvod navigácie: odkaz na kartu odovzdá v `location.state`, odkiaľ prišiel, aby drobčeky ukázali správnu cestu. */
export interface Origin {
  /** Text drobčeka (už preložený), napr. „Plán ubytovania / 17. 11. ALF 102“. */
  label: string;
  to: string;
}

export interface NavState {
  origin?: Origin;
}

export const originState = (label: string, to: string): NavState => ({ origin: { label, to } });

export function readOrigin(state: unknown): Origin | undefined {
  const o = (state as NavState | null | undefined)?.origin;
  return o && typeof o.label === 'string' && typeof o.to === 'string' ? o : undefined;
}
