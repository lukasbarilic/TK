import { buildPlanVM } from './buildPlanVM';
import type { Plan, PlanFilters } from './types';

// okno 17. 11. – 1. 12. 2026 (15 dní, stĺpce 0–14)
const filters: PlanFilters = { receptionId: 2, buildingIds: [], from: '2026-11-17', to: '2026-12-01', freeOnly: false, fullyFree: false };

const room = (roomId: number, extra: Partial<NonNullable<Plan['rooms']>[number]> = {}): NonNullable<Plan['rooms']>[number] => ({
  roomId,
  buildingId: 1,
  building: 'ALF',
  number: String(100 + roomId),
  category: { id: 2, code: 'K2' },
  beds: [
    { bedId: roomId * 10 + 1, number: 1, type: 'REGULAR' },
    { bedId: roomId * 10 + 2, number: 2, type: 'REGULAR' },
  ],
  bars: [],
  ...extra,
});

const stay = (stayId: number, bedId: number, from: string, to: string, extra = {}) => ({
  type: 'STAY', stayId, reservationId: 140, bedId, from, to, stayFrom: from, stayTo: to, label: 'Testovský A.', status: 'CHECKED_IN', locked: false, flags: [], ...extra,
});

const plan = (rooms: Plan['rooms']): Plan => ({ from: '2026-11-17', to: '2026-12-01', receptions: [{ receptionId: 2, businessDate: '2026-11-18' }], rooms });

const beds = (vm: ReturnType<typeof buildPlanVM>) => vm.rows.filter((r) => r.kind === 'bed');

describe('buildPlanVM', () => {
  it('zostaví dni, obchodný deň a riadky izba + lôžka', () => {
    const vm = buildPlanVM(plan([room(1)]), filters);
    expect(vm.days).toHaveLength(15);
    expect(vm.days[0]).toBe('2026-11-17');
    expect(vm.businessDates).toEqual({ 2: '2026-11-18' });
    expect(vm.rows.map((r) => r.kind)).toEqual(['room', 'bed', 'bed']);
    expect(vm.rows[0]).toMatchObject({ kind: 'room', roomId: 1, label: '101', building: 'ALF', category: 'K2', receptionId: 2 });
  });

  it('prázdna izba: izba a lôžka bez pásikov', () => {
    const vm = buildPlanVM(plan([room(1)]), filters);
    expect(beds(vm).every((r) => r.kind === 'bed' && r.bars.length === 0)).toBe(true);
    expect(buildPlanVM(plan([]), filters).rows).toEqual([]);
    expect(buildPlanVM(undefined, filters).rows).toEqual([]);
  });

  it('pásik v okne: stĺpce podľa dátumov, bez orezania', () => {
    const vm = buildPlanVM(plan([room(1, { bars: [stay(812, 11, '2026-11-19', '2026-11-22')] })]), filters);
    const bar = beds(vm)[0].kind === 'bed' ? (beds(vm)[0] as { bars: unknown[] }).bars[0] : null;
    expect(bar).toMatchObject({ id: 'stay:812', type: 'STAY', startCol: 2, endCol: 5, clippedStart: false, clippedEnd: false, label: 'Testovský A.', stayId: 812, reservationId: 140 });
  });

  it('orezanie na okno: skutočné dátumy ostanú, stĺpce sa orežú', () => {
    const vm = buildPlanVM(
      plan([room(1, { bars: [stay(1, 11, '2026-11-10', '2026-11-20'), stay(2, 12, '2026-11-28', '2026-12-10'), stay(3, 11, '2026-11-01', '2026-11-05')] })]),
      filters,
    );
    const [b1, b2] = (beds(vm)[0] as { bars: { id: string }[] }).bars.concat((beds(vm)[1] as { bars: { id: string }[] }).bars);
    expect(b1).toMatchObject({ id: 'stay:1', from: '2026-11-10', to: '2026-11-20', startCol: 0, endCol: 3, clippedStart: true, clippedEnd: false });
    expect(b2).toMatchObject({ id: 'stay:2', from: '2026-11-28', to: '2026-12-10', startCol: 11, endCol: 14, clippedStart: false, clippedEnd: true });
    // pásik úplne pred oknom sa nezobrazí
    expect((beds(vm)[0] as { bars: unknown[] }).bars).toHaveLength(1);
  });

  it('odchod v prvý deň okna a príchod v posledný deň okna sa ešte zobrazia (pol dňa)', () => {
    const vm = buildPlanVM(
      plan([room(1, { bars: [stay(1, 11, '2026-11-10', '2026-11-17'), stay(2, 12, '2026-12-01', '2026-12-05')] })]),
      filters,
    );
    expect((beds(vm)[0] as { bars: { startCol: number; endCol: number }[] }).bars[0]).toMatchObject({ startCol: 0, endCol: 0 });
    expect((beds(vm)[1] as { bars: { startCol: number; endCol: number }[] }).bars[0]).toMatchObject({ startCol: 14, endCol: 14 });
  });

  it('blokácia izby sa zobrazí na všetkých lôžkach izby, aj na prístelke', () => {
    const block = { type: 'ROOM_BLOCK', blockId: 5, reason: 'Maľovanie', from: '2026-11-20', to: '2026-11-26' };
    const r = room(1, {
      beds: [
        { bedId: 11, number: 1, type: 'REGULAR' },
        { bedId: 12, number: 2, type: 'REGULAR' },
        { bedId: 13, number: 3, type: 'EXTRA' },
      ],
      bars: [block],
    });
    const vm = buildPlanVM(plan([r]), filters);
    const rows = beds(vm) as { bedId: number; bars: { id: string; bedId: number | null; startCol: number; endCol: number; reason?: string }[] }[];
    expect(rows).toHaveLength(3);
    for (const row of rows) {
      expect(row.bars).toHaveLength(1);
      expect(row.bars[0]).toMatchObject({ id: 'block:5', type: 'ROOM_BLOCK', bedId: null, startCol: 3, endCol: 9, reason: 'Maľovanie' });
    }
  });

  it('extra lôžko: riadok má extra=true; showExtraBeds=false skryje len prázdnu prístelku', () => {
    const r = room(1, {
      beds: [
        { bedId: 11, number: 1, type: 'REGULAR' },
        { bedId: 13, number: 3, type: 'EXTRA' },
      ],
      bars: [],
    });
    const vm = buildPlanVM(plan([r]), filters);
    expect((beds(vm) as { extra: boolean }[]).map((b) => b.extra)).toEqual([false, true]);
    expect(beds(buildPlanVM(plan([r]), filters, { showExtraBeds: false }))).toHaveLength(1);

    const occupied = room(1, { beds: r.beds, bars: [stay(9, 13, '2026-11-18', '2026-11-20')] });
    expect(beds(buildPlanVM(plan([occupied]), filters, { showExtraBeds: false }))).toHaveLength(2);
  });

  it('držané lôžko má vlastné id a ide na svoje lôžko', () => {
    const r = room(1, { bars: [stay(812, 11, '2026-11-18', '2026-11-20'), { type: 'HELD_BED', byStayId: 812, byReservationId: 140, bedId: 12, from: '2026-11-18', to: '2026-11-20' }] });
    const rows = beds(buildPlanVM(plan([r]), filters)) as { bars: { id: string; type: string }[] }[];
    expect(rows[1].bars[0]).toMatchObject({ id: 'held:812:12', type: 'HELD_BED' });
  });

  it('neznáme pásiky (turnusy kontingentov) sa vynechajú', () => {
    const r = room(1, { bars: [{ type: 'CONTINGENT_TURN', turnId: 3, from: '2026-11-20', to: '2026-11-25' } as never] });
    expect((beds(buildPlanVM(plan([r]), filters))[0] as { bars: unknown[] }).bars).toEqual([]);
  });

  describe('filtre', () => {
    const rooms = [
      room(1, { bars: [stay(1, 11, '2026-11-18', '2026-11-20'), stay(2, 12, '2026-11-18', '2026-11-20')] }), // obsadená
      room(2, { bars: [stay(3, 21, '2026-11-18', '2026-11-20')] }), // jedno lôžko voľné
      room(3), // prázdna
      room(4, { bars: [{ type: 'ROOM_BLOCK', blockId: 9, from: '2026-11-18', to: '2026-11-20' }] }), // blokovaná
      room(5, { buildingId: 2, building: 'VIL' }),
    ];
    const ids = (f: Partial<PlanFilters>) =>
      buildPlanVM(plan(rooms), { ...filters, ...f })
        .rows.filter((r) => r.kind === 'room')
        .map((r) => r.roomId);

    it('bez filtra všetky izby, zoradené podľa budovy a čísla', () => {
      expect(ids({})).toEqual([1, 2, 3, 4, 5]);
    });
    it('budovy', () => {
      expect(ids({ buildingIds: [2] })).toEqual([5]);
      expect(ids({ buildingIds: [1] })).toEqual([1, 2, 3, 4]);
    });
    it('freeOnly: izba bez blokácie s aspoň jedným voľným riadnym lôžkom', () => {
      expect(ids({ freeOnly: true })).toEqual([2, 3, 5]);
    });
    it('fullyFree: izba bez pobytu a bez blokácie', () => {
      expect(ids({ fullyFree: true })).toEqual([3, 5]);
    });
    it('pobyt mimo zobrazeného rozsahu izbu neobsadzuje', () => {
      const r = [room(1, { bars: [stay(1, 11, '2026-11-10', '2026-11-17'), stay(2, 12, '2026-11-10', '2026-11-17')] })];
      const vm = buildPlanVM(plan(r), { ...filters, freeOnly: true });
      expect(vm.rows.filter((x) => x.kind === 'room')).toHaveLength(1); // odchod v prvý deň noc neobsadzuje
    });
    it('držané lôžko sa počíta ako obsadené pre freeOnly', () => {
      const r = [room(1, { bars: [stay(1, 11, '2026-11-18', '2026-11-20'), { type: 'HELD_BED', byStayId: 1, bedId: 12, from: '2026-11-18', to: '2026-11-20' }] })];
      expect(buildPlanVM(plan(r), { ...filters, freeOnly: true }).rows).toEqual([]);
    });
  });
});
