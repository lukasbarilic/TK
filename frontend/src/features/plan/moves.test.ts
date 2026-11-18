import {
  batchFromParking,
  canTouch,
  failedStayId,
  hideParked,
  isBackdated,
  isRangeFree,
  moveFromDrop,
  occupiedByParking,
  parkingReducer,
  resizeFromDrag,
  type MovePerms,
  type ParkedItem,
} from './moves';
import type { BarVM, PlanRow } from './types';

const bar = (o: Partial<BarVM> = {}): BarVM => ({
  id: 'stay:812', type: 'STAY', bedId: 11, from: '2026-11-19', to: '2026-11-24', startCol: 2, endCol: 7,
  clippedStart: false, clippedEnd: false, label: 'Testovský A.', status: 'RESERVED', stayId: 812, reservationId: 912, ...o,
});
const perms: MovePerms = { move: true, moveLocked: false, backdate: false, businessDate: '2026-11-18' };
const item = (o: Partial<ParkedItem> = {}): ParkedItem => ({ stayId: 812, label: 'Testovský A.', from: '2026-11-19', to: '2026-11-24', originBedId: 11, ...o });

describe('moveFromDrop – príkaz presunu z pozície pustenia', () => {
  it('celý pobyt na iné lôžko: bez fromDate', () => {
    expect(moveFromDrop(bar(), 11, 21, '2026-11-21', false)).toEqual({ stayId: 812, targetBedId: 21 });
  });
  it('iné lôžko tej istej izby je bežný presun', () => {
    expect(moveFromDrop(bar(), 11, 12, '2026-11-20', false)).toEqual({ stayId: 812, targetBedId: 12 });
  });
  it('Alt: presun od dňa pod kurzorom', () => {
    expect(moveFromDrop(bar(), 11, 21, '2026-11-21', true)).toEqual({ stayId: 812, targetBedId: 21, fromDate: '2026-11-21' });
  });
  it('Alt na začiatku pobytu alebo pred ním = celý pobyt; na konci alebo po ňom = nič', () => {
    expect(moveFromDrop(bar(), 11, 21, '2026-11-19', true)).toEqual({ stayId: 812, targetBedId: 21 });
    expect(moveFromDrop(bar(), 11, 21, '2026-11-17', true)).toEqual({ stayId: 812, targetBedId: 21 });
    expect(moveFromDrop(bar(), 11, 21, '2026-11-24', true)).toBeNull();
  });
  it('pustenie na pôvodné lôžko ani pásik, ktorý nie je pobyt, nič nezmení', () => {
    expect(moveFromDrop(bar(), 11, 11, '2026-11-21', false)).toBeNull();
    expect(moveFromDrop(bar({ type: 'HELD_BED', stayId: undefined }), 11, 21, '2026-11-21', false)).toBeNull();
  });
});

describe('resizeFromDrag – zmena dĺžky', () => {
  it('predĺženie a skrátenie o dni', () => {
    expect(resizeFromDrag(bar(), 2)).toBe('2026-11-26');
    expect(resizeFromDrag(bar(), -2)).toBe('2026-11-22');
  });
  it('bez posunu alebo koniec na/pred začiatkom = žiadna zmena', () => {
    expect(resizeFromDrag(bar(), 0)).toBeNull();
    expect(resizeFromDrag(bar(), -5)).toBeNull();
    expect(resizeFromDrag(bar(), -6)).toBeNull();
  });
});

describe('oprávnenia (len skrytie v UI)', () => {
  it('zamknutý pobyt len s STAY_MOVE_LOCKED', () => {
    expect(canTouch(bar({ locked: true }), perms)).toBe(false);
    expect(canTouch(bar({ locked: true }), { ...perms, moveLocked: true })).toBe(true);
    expect(canTouch(bar(), perms)).toBe(true);
  });
  it('bez STAY_MOVE, ukončený pobyt a blokácia sa nehýbu', () => {
    expect(canTouch(bar(), { ...perms, move: false })).toBe(false);
    expect(canTouch(bar({ status: 'CHECKED_OUT' }), perms)).toBe(false);
    expect(canTouch(bar({ type: 'ROOM_BLOCK', stayId: undefined }), perms)).toBe(false);
  });
  it('spätná zmena len so STAY_BACKDATE', () => {
    expect(isBackdated('2026-11-17', perms)).toBe(true);
    expect(isBackdated('2026-11-18', perms)).toBe(false);
    expect(isBackdated('2026-11-17', { ...perms, backdate: true })).toBe(false);
  });
});

describe('isRangeFree – vyznačenie cieľa', () => {
  const other = bar({ id: 'stay:5', stayId: 5, from: '2026-11-22', to: '2026-11-26' });
  it('prekrytie s iným pobytom = obsadené; odchod a príchod v ten istý deň nie', () => {
    expect(isRangeFree([other], '2026-11-19', '2026-11-24')).toBe(false);
    expect(isRangeFree([other], '2026-11-19', '2026-11-22')).toBe(true);
    expect(isRangeFree([other], '2026-11-26', '2026-11-28')).toBe(true);
  });
  it('ignoruje ťahaný pobyt a lôžka, ktoré drží', () => {
    const self = bar({ id: 'held:812:12', type: 'HELD_BED', stayId: 812, from: '2026-11-19', to: '2026-11-24' });
    expect(isRangeFree([self], '2026-11-19', '2026-11-24', 812)).toBe(true);
    expect(isRangeFree([self], '2026-11-19', '2026-11-24', 1)).toBe(false);
  });
  it('berie do úvahy pobyty z odkladiska, ktoré už majú cieľ', () => {
    const extra = occupiedByParking([item({ targetBedId: 21 }), item({ stayId: 9, targetBedId: 22 })], 9);
    expect(isRangeFree([], '2026-11-20', '2026-11-21', undefined, extra, 21)).toBe(false);
    expect(isRangeFree([], '2026-11-20', '2026-11-21', undefined, extra, 22)).toBe(true);
  });
});

describe('odkladisko', () => {
  it('uloženie, určenie cieľa, vrátenie a vyprázdnenie', () => {
    let s = parkingReducer([], { type: 'park', item: item() });
    s = parkingReducer(s, { type: 'park', item: item({ stayId: 813 }) });
    s = parkingReducer(s, { type: 'place', stayId: 812, bedId: 21 });
    expect(s.map((i) => [i.stayId, i.targetBedId])).toEqual([[812, 21], [813, undefined]]);
    expect(batchFromParking(s)).toEqual([{ stayId: 812, targetBedId: 21 }]); // len položky s cieľom
    expect(parkingReducer(s, { type: 'remove', stayId: 812 }).map((i) => i.stayId)).toEqual([813]);
    expect(parkingReducer(s, { type: 'clear' })).toEqual([]);
  });
  it('opätovné uloženie toho istého pobytu zruší jeho cieľ', () => {
    let s = parkingReducer([], { type: 'park', item: item() });
    s = parkingReducer(s, { type: 'place', stayId: 812, bedId: 21 });
    s = parkingReducer(s, { type: 'park', item: item() });
    expect(s).toHaveLength(1);
    expect(s[0].targetBedId).toBeUndefined();
  });
  it('hideParked skryje pásiky pobytov z odkladiska a nezmení ostatné riadky', () => {
    const rows: PlanRow[] = [
      { kind: 'bed', roomId: 1, bedId: 11, bedLabel: '1', extra: false, bars: [bar(), bar({ id: 'stay:5', stayId: 5 })] },
      { kind: 'bed', roomId: 2, bedId: 21, bedLabel: '1', extra: false, bars: [bar({ id: 'stay:6', stayId: 6 })] },
    ];
    const out = hideParked(rows, [item()]);
    expect((out[0] as { bars: BarVM[] }).bars.map((b) => b.stayId)).toEqual([5]);
    expect(out[1]).toBe(rows[1]);
    expect(hideParked(rows, [])).toBe(rows);
  });
  it('failedStayId nájde položku podľa čísla pobytu v texte chyby', () => {
    const items = [item(), item({ stayId: 813 })];
    expect(failedStayId('Pobyt 813 už je na tomto lôžku.', items)).toBe(813);
    expect(failedStayId('Chyba v dávke', items)).toBeUndefined();
    expect(failedStayId('Pobyt 8120 …', items)).toBeUndefined();
  });
});
