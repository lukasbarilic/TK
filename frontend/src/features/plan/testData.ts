import type { Plan } from './types';

/** Fiktívne dáta pre testy plánu. Obchodný deň 18. 11. 2026, okno 17. 11. – 2. 12. */
export const BUSINESS_DATE = '2026-11-18';
export const WINDOW = { from: '2026-11-17', to: '2026-12-02' };

export const stayBar = (stayId: number, bedId: number, from: string, to: string, label = 'Testovský A.', status = 'CHECKED_IN') => ({
  type: 'STAY', stayId, reservationId: 100 + stayId, bedId, from, to, stayFrom: from, stayTo: to, label, status, locked: false, flags: [],
});

export function samplePlan(): Plan {
  return {
    from: WINDOW.from,
    to: WINDOW.to,
    receptions: [{ receptionId: 2, businessDate: BUSINESS_DATE }],
    rooms: [
      {
        roomId: 1, buildingId: 1, building: 'ALF', number: '101', category: { id: 2, code: 'K2' },
        beds: [
          { bedId: 11, number: 1, type: 'REGULAR' },
          { bedId: 12, number: 2, type: 'REGULAR' },
          { bedId: 13, number: 3, type: 'EXTRA' },
        ],
        bars: [
          stayBar(812, 11, '2026-11-19', '2026-11-22'),
          { type: 'HELD_BED', byStayId: 812, byReservationId: 912, bedId: 12, from: '2026-11-19', to: '2026-11-22' },
        ],
      },
      {
        roomId: 2, buildingId: 1, building: 'ALF', number: '102', category: { id: 2, code: 'K2' },
        beds: [{ bedId: 21, number: 1, type: 'REGULAR' }, { bedId: 22, number: 2, type: 'REGULAR' }],
        bars: [],
      },
      {
        roomId: 3, buildingId: 2, building: 'VIL', number: '201', category: { id: 3, code: 'K1' },
        beds: [{ bedId: 31, number: 1, type: 'REGULAR' }],
        bars: [{ type: 'ROOM_BLOCK', blockId: 5, reason: 'Maľovanie', from: '2026-11-25', to: '2026-11-28' }],
      },
    ],
  };
}

export const sampleCard = {
  id: 912,
  reservationNumber: 'R-2026-0912',
  status: 'CHECKED_IN' as const,
  arrivalDate: '2026-11-19',
  departureDate: '2026-11-22',
  guests: [{ id: 1, firstName: 'Anna', lastName: 'Testovská', primaryGuest: true }],
};

/** Veľký plán na meranie výkonu: `rooms` izieb po 2 lôžka, každé obsadené pobytmi cez `days` dní. */
export function generatePlan(rooms: number, days: number): Plan {
  const out: NonNullable<Plan['rooms']> = [];
  for (let r = 1; r <= rooms; r++) {
    const beds = [
      { bedId: r * 10 + 1, number: 1, type: 'REGULAR' },
      { bedId: r * 10 + 2, number: 2, type: 'REGULAR' },
      { bedId: r * 10 + 3, number: 3, type: 'EXTRA' },
    ];
    const bars: NonNullable<Plan['rooms']>[number]['bars'] = [];
    for (let s = 0; s * 5 < days; s++) {
      const from = new Date(Date.UTC(2026, 10, 17 + s * 5)).toISOString().slice(0, 10);
      const to = new Date(Date.UTC(2026, 10, 17 + s * 5 + 4)).toISOString().slice(0, 10);
      bars.push(stayBar(r * 100 + s, r * 10 + 1, from, to, `Host ${r}-${s}`, s % 2 ? 'RESERVED' : 'CHECKED_IN'));
      if (s % 3 === 0) bars.push({ type: 'HELD_BED', byStayId: r * 100 + s, byReservationId: 1, bedId: r * 10 + 2, from, to });
    }
    out.push({
      roomId: r, buildingId: 1 + (r % 3), building: `B${1 + (r % 3)}`, number: String(r), category: { id: 1, code: 'K2' }, beds, bars,
    });
  }
  return { from: '2026-11-17', to: '2026-12-02', receptions: [{ receptionId: 2, businessDate: BUSINESS_DATE }], rooms: out };
}
