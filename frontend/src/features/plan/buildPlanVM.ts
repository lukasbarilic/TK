import { diffDays, listDays } from '../../shared/time';
import type { BarVM, Plan, PlanFilters, PlanPreferences, PlanRow, PlanVM } from './types';

type RoomDto = NonNullable<Plan['rooms']>[number];
type BarDto = NonNullable<RoomDto['bars']>[number];

/** Pole pásika; pásiky sú zjednotenie typov, preto sa čítajú voľne. */
type Loose = {
  type?: string;
  stayId?: number;
  byStayId?: number;
  byReservationId?: number;
  reservationId?: number;
  blockId?: number;
  bedId?: number;
  from?: string;
  to?: string;
  stayFrom?: string;
  stayTo?: string;
  blockFrom?: string;
  blockTo?: string;
  label?: string;
  status?: string;
  locked?: boolean;
  flags?: string[];
  hasNote?: boolean;
  commentCount?: number;
  partyId?: number;
  reason?: string;
};

function toBar(dto: BarDto, vmFrom: string, lastCol: number): BarVM | null {
  const b = dto as Loose;
  const from = b.stayFrom ?? b.blockFrom ?? b.from;
  const to = b.stayTo ?? b.blockTo ?? b.to;
  if (!from || !to) return null;
  const rawStart = diffDays(vmFrom, from);
  const rawEnd = diffDays(vmFrom, to);
  // Pásik ide od polovice dňa `from` po polovicu dňa `to`: viditeľný je, ak sa dotýka okna.
  if (rawEnd < 0 || rawStart > lastCol) return null;
  const common = {
    from,
    to,
    startCol: Math.max(0, rawStart),
    endCol: Math.min(lastCol, rawEnd),
    clippedStart: rawStart < 0,
    clippedEnd: rawEnd > lastCol,
  };
  if (b.type === 'STAY' && b.stayId !== undefined) {
    return {
      ...common,
      id: `stay:${b.stayId}`,
      type: 'STAY',
      bedId: b.bedId ?? null,
      label: b.label,
      status: b.status,
      locked: b.locked,
      flags: b.flags,
      hasNote: b.hasNote,
      commentCount: b.commentCount,
      stayId: b.stayId,
      reservationId: b.reservationId,
      partyId: b.partyId,
    };
  }
  if (b.type === 'HELD_BED' && b.byStayId !== undefined && b.bedId !== undefined) {
    return {
      ...common,
      id: `held:${b.byStayId}:${b.bedId}`,
      type: 'HELD_BED',
      bedId: b.bedId,
      stayId: b.byStayId,
      reservationId: b.byReservationId,
    };
  }
  if (b.type === 'ROOM_BLOCK' && b.blockId !== undefined) {
    return { ...common, id: `block:${b.blockId}`, type: 'ROOM_BLOCK', bedId: null, reason: b.reason };
  }
  return null; // iné typy (napr. turnusy kontingentov) sú mimo rozsahu F06
}

/** Obsadzuje pásik noci rozsahu `[from, to]` (dni vrátane)? Odchod v prvý deň noc neobsadzuje. */
const occupies = (b: BarVM, from: string, to: string) => b.from <= to && b.to > from;

function matchesFilter(room: RoomDto, bars: BarVM[], f: PlanFilters): boolean {
  const live = bars.filter((b) => occupies(b, f.from, f.to));
  const stays = live.filter((b) => b.type === 'STAY');
  const blocked = live.some((b) => b.type === 'ROOM_BLOCK');
  if (f.fullyFree && (stays.length > 0 || blocked)) return false;
  if (f.occupiedOnly && stays.length === 0) return false;
  if (f.freeOnly) {
    if (blocked) return false;
    const taken = new Set(live.filter((b) => b.type !== 'ROOM_BLOCK').map((b) => b.bedId));
    return (room.beds ?? []).some((bed) => bed.type !== 'EXTRA' && !taken.has(bed.bedId ?? -1));
  }
  return true;
}

/**
 * Model pre zobrazenie plánu (DLD kap. 9.1). Čistá funkcia: orezáva pásiky na okno `filters.from..to`,
 * rozkladá blokáciu izby na všetky lôžka, uplatňuje filtre budov, `freeOnly`, `fullyFree` a `occupiedOnly`.
 * Preferencie: `showExtraBeds === false` skryje prístelky bez pásikov.
 */
export function buildPlanVM(plan: Plan | undefined, filters: PlanFilters, prefs: PlanPreferences = {}): PlanVM {
  const days = listDays(filters.from, filters.to);
  const lastCol = days.length - 1;
  const businessDates: Record<number, string> = {};
  for (const r of plan?.receptions ?? []) {
    if (r.receptionId !== undefined && r.businessDate) businessDates[r.receptionId] = r.businessDate;
  }

  const rows: PlanRow[] = [];
  const rooms = [...(plan?.rooms ?? [])].sort(
    (a, b) =>
      (a.building ?? '').localeCompare(b.building ?? '') ||
      (a.number ?? '').localeCompare(b.number ?? '', undefined, { numeric: true }),
  );
  for (const room of rooms) {
    if (room.roomId === undefined) continue;
    if (filters.buildingIds.length > 0 && !filters.buildingIds.includes(room.buildingId ?? -1)) continue;
    const bars = days.length === 0 ? [] : (room.bars ?? []).flatMap((d) => toBar(d, filters.from, lastCol) ?? []);
    if (!matchesFilter(room, bars, filters)) continue;

    rows.push({
      kind: 'room',
      roomId: room.roomId,
      label: room.number ?? String(room.roomId),
      building: room.building ?? '',
      category: room.category?.code ?? '',
      receptionId: filters.receptionId,
    });
    const blocks = bars.filter((b) => b.type === 'ROOM_BLOCK');
    for (const bed of room.beds ?? []) {
      if (bed.bedId === undefined) continue;
      const extra = bed.type === 'EXTRA';
      const own = bars.filter((b) => b.bedId === bed.bedId);
      if (extra && prefs.showExtraBeds === false && own.length === 0) continue;
      rows.push({
        kind: 'bed',
        roomId: room.roomId,
        bedId: bed.bedId,
        bedLabel: String(bed.number ?? ''),
        extra,
        bars: [...own, ...blocks].sort((a, b) => a.startCol - b.startCol),
      });
    }
  }
  return { from: filters.from, to: filters.to, days, businessDates, rows };
}
