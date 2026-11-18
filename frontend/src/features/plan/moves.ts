import { addDays } from '../../shared/time';
import type { BarVM, PlanRow } from './types';

/** Príkaz presunu pobytu (`POST /api/stays/{id}/move`, položka dávky). `fromDate` = presun len od dňa. */
export interface MoveCmd {
  stayId: number;
  targetBedId: number;
  fromDate?: string;
}

/** Oprávnenia, ktoré UI používa len na skrytie akcií; rozhoduje server (DLD kap. 9.3). */
export interface MovePerms {
  move: boolean;
  moveLocked: boolean;
  backdate: boolean;
  /** Obchodný deň recepcie; zmena pred ním vyžaduje `STAY_BACKDATE`. */
  businessDate?: string;
}

export const isMovable = (bar: BarVM): boolean => bar.type === 'STAY' && bar.stayId !== undefined;

/** Smie používateľ so zamknutým/nezamknutým pásikom hýbať (skrytie v UI)? */
export const canTouch = (bar: BarVM, perms: MovePerms): boolean =>
  perms.move && isMovable(bar) && (!bar.locked || perms.moveLocked) && bar.status !== 'CHECKED_OUT';

/** Začína zmena pred obchodným dňom bez `STAY_BACKDATE`? */
export const isBackdated = (start: string, perms: MovePerms): boolean =>
  !perms.backdate && perms.businessDate !== undefined && start < perms.businessDate;

/**
 * Príkaz presunu z pozície pustenia:
 * - celý pobyt na iné lôžko (aj tej istej izby): bez `fromDate`;
 * - `partial` (Alt) a pustenie na deň vnútri pobytu: `fromDate` = deň pod kurzorom;
 *   deň na/pred začiatkom pobytu = celý pobyt, deň na/po konci = nič.
 */
export function moveFromDrop(
  bar: BarVM,
  originBedId: number,
  targetBedId: number,
  dropDay: string | undefined,
  partial: boolean,
): MoveCmd | null {
  if (!isMovable(bar) || bar.stayId === undefined || targetBedId === originBedId) return null;
  const stayId = bar.stayId;
  if (partial && dropDay !== undefined) {
    if (dropDay >= bar.to) return null;
    if (dropDay > bar.from) return { stayId, targetBedId, fromDate: dropDay };
  }
  return { stayId, targetBedId };
}

/** Prvý deň, ktorého sa príkaz dotkne. */
export const moveStart = (bar: BarVM, cmd: MoveCmd): string => cmd.fromDate ?? bar.from;

/** Nový koniec pobytu po ťahaní pravého okraja o `deltaDays`; `null` = bez zmeny alebo neplatný. */
export function resizeFromDrag(bar: BarVM, deltaDays: number): string | null {
  if (!isMovable(bar) || deltaDays === 0) return null;
  const to = addDays(bar.to, deltaDays);
  return to > bar.from ? to : null;
}

/** Obsadená časť lôžka (napr. pobyt z odkladiska, ktorý už má cieľ). */
export interface Occupied {
  bedId: number;
  from: string;
  to: string;
}

/**
 * Je lôžko podľa lokálnych dát voľné v nociach `[from, to)`? Len pomoc pri ťahaní – rozhoduje server.
 * Pásiky pobytu `ignoreStayId` (a ním držané lôžka) sa ignorujú.
 */
export function isRangeFree(bars: BarVM[], from: string, to: string, ignoreStayId?: number, extra: Occupied[] = [], bedId?: number): boolean {
  if (bars.some((b) => b.from < to && from < b.to && !(ignoreStayId !== undefined && b.stayId === ignoreStayId))) return false;
  return !extra.some((o) => (bedId === undefined || o.bedId === bedId) && o.from < to && from < o.to);
}

/** Pobyt v odkladisku (celý pobyt; `targetBedId` až po pustení na lôžko). */
export interface ParkedItem {
  stayId: number;
  label: string;
  from: string;
  to: string;
  originBedId: number;
  targetBedId?: number;
}

export type ParkingAction =
  | { type: 'park'; item: ParkedItem }
  | { type: 'place'; stayId: number; bedId: number }
  | { type: 'remove'; stayId: number }
  | { type: 'clear' };

/** Stav odkladiska (DLD kap. 9.3): len UI, nikam sa neukladá. */
export function parkingReducer(state: ParkedItem[], a: ParkingAction): ParkedItem[] {
  switch (a.type) {
    case 'park':
      return [...state.filter((i) => i.stayId !== a.item.stayId), { ...a.item, targetBedId: undefined }];
    case 'place':
      return state.map((i) => (i.stayId === a.stayId ? { ...i, targetBedId: a.bedId } : i));
    case 'remove':
      return state.filter((i) => i.stayId !== a.stayId);
    case 'clear':
      return [];
  }
}

/** Dávka pre `POST /api/stays/moves:batch`: len položky s cieľom. */
export const batchFromParking = (items: ParkedItem[]): MoveCmd[] =>
  items.flatMap((i) => (i.targetBedId === undefined ? [] : [{ stayId: i.stayId, targetBedId: i.targetBedId }]));

/** Lôžka, ktoré už zaberajú pobyty z odkladiska s cieľom. */
export const occupiedByParking = (items: ParkedItem[], exceptStayId?: number): Occupied[] =>
  items.flatMap((i) => (i.targetBedId === undefined || i.stayId === exceptStayId ? [] : [{ bedId: i.targetBedId, from: i.from, to: i.to }]));

/** Skryje v pláne pásiky pobytov z odkladiska (a nimi držané lôžka); riadky bez zmeny ostanú rovnaké objekty. */
export function hideParked(rows: PlanRow[], parked: ParkedItem[]): PlanRow[] {
  if (parked.length === 0) return rows;
  const ids = new Set(parked.map((p) => p.stayId));
  return rows.map((r) => {
    if (r.kind !== 'bed' || !r.bars.some((b) => b.stayId !== undefined && ids.has(b.stayId))) return r;
    return { ...r, bars: r.bars.filter((b) => b.stayId === undefined || !ids.has(b.stayId)) };
  });
}

/** Ktorá položka dávky zlyhala? Server uvádza číslo pobytu v texte chyby (`Pobyt 812 …`). */
export function failedStayId(detail: string | undefined, items: ParkedItem[]): number | undefined {
  if (!detail) return undefined;
  return items.find((i) => new RegExp(`(^|\\D)${i.stayId}(\\D|$)`).test(detail))?.stayId;
}
