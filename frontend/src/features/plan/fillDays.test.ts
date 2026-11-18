import { fillDays, MAX_PLAN_DAYS } from './fillDays';

describe('fillDays', () => {
  it('bez známej šírky nič nepridá', () => {
    expect(fillDays(0, 24, 200, 15)).toEqual({ show: 0, fetch: 0 });
  });

  it('doplní dni do šírky a načíta celé týždne', () => {
    // (1300 - 200 - 20) / 24 = 45 dní, z nastavení 15 → 30 navyše, načíta sa 35
    expect(fillDays(1300, 24, 200, 15)).toEqual({ show: 30, fetch: 35 });
  });

  it('úzke okno rozsah z nastavení nezmenší', () => {
    expect(fillDays(400, 56, 200, 15)).toEqual({ show: 0, fetch: 0 });
  });

  it('širší zoom zobrazí menej dní', () => {
    expect(fillDays(1300, 56, 200, 15).show).toBe(4);
  });

  it(`spolu najviac ${MAX_PLAN_DAYS} dní`, () => {
    const r = fillDays(10000, 24, 200, 15);
    expect(15 + r.show).toBe(MAX_PLAN_DAYS);
    expect(15 + r.fetch).toBe(MAX_PLAN_DAYS);
  });
});
