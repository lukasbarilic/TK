import type { Plan } from './types';

export interface DayCounts {
  /** „Dnes príde“: rezervované karty s príchodom v obchodný deň. */
  arriving: number;
  /** „Odíde“: prítomné karty s odchodom v obchodný deň. */
  departing: number;
  /** „V dome“: prítomné karty (hosť je v dome do odchodu). */
  inHouse: number;
}

type Loose = { type?: string; status?: string; reservationId?: number; from?: string; to?: string; stayFrom?: string; stayTo?: string };

/** Počty kariet v obchodný deň z pásikov pobytov plánu recepcie (karta sa počíta raz, aj keď má viac pobytov). */
export function dayCounts(plan: Plan | undefined, businessDate: string | undefined): DayCounts {
  const arriving = new Set<number>();
  const departing = new Set<number>();
  const inHouse = new Set<number>();
  if (plan && businessDate) {
    for (const room of plan.rooms ?? []) {
      for (const dto of room.bars ?? []) {
        const b = dto as Loose;
        const from = b.stayFrom ?? b.from;
        const to = b.stayTo ?? b.to;
        if (b.type !== 'STAY' || b.reservationId === undefined || !from || !to) continue;
        if (b.status === 'RESERVED' && from === businessDate) arriving.add(b.reservationId);
        if (b.status === 'CHECKED_IN') {
          if (from <= businessDate && businessDate <= to) inHouse.add(b.reservationId);
          if (to === businessDate) departing.add(b.reservationId);
        }
      }
    }
  }
  return { arriving: arriving.size, departing: departing.size, inHouse: inHouse.size };
}
