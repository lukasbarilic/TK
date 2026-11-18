import { focusOffset } from './focus';

describe('focusOffset', () => {
  it('dátum v rozsahu z nastavení nič neposúva', () => {
    expect(focusOffset('2026-11-18', '2026-11-20', 1, 13)).toBe(0);
  });

  it('vzdialený dátum posunie rozsah, aby bol druhým dňom', () => {
    // 27. 1. 2027 je 70 dní po 18. 11.; rozsah začne 26. 1. 2027
    expect(focusOffset('2026-11-18', '2027-01-27', 1, 13)).toBe(70);
  });

  it('dátum v minulosti posunie rozsah dozadu', () => {
    expect(focusOffset('2026-11-18', '2026-09-01', 1, 13)).toBe(-78);
  });

  it('dátum blízko konca rozsahu posunie, aby bol vidieť aj týždeň po ňom', () => {
    expect(focusOffset('2026-11-18', '2026-11-29', 1, 13)).toBe(11);
  });
});
