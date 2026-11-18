import { addDays, diffDays, formatDayShort, listDays } from '.';

describe('shared/time', () => {
  it('addDays pridáva a odoberá dni cez hranice mesiaca a roka', () => {
    expect(addDays('2026-11-17', 1)).toBe('2026-11-18');
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
    expect(addDays('2026-11-17', 0)).toBe('2026-11-17');
  });

  it('diffDays je znamienkový', () => {
    expect(diffDays('2026-11-17', '2026-11-20')).toBe(3);
    expect(diffDays('2026-11-20', '2026-11-17')).toBe(-3);
    expect(diffDays('2026-11-17', '2026-11-17')).toBe(0);
  });

  it('listDays vráti oba konce, pri opačnom poradí prázdne pole', () => {
    expect(listDays('2026-11-29', '2026-12-02')).toEqual([
      '2026-11-29', '2026-11-30', '2026-12-01', '2026-12-02',
    ]);
    expect(listDays('2026-11-17', '2026-11-17')).toEqual(['2026-11-17']);
    expect(listDays('2026-11-18', '2026-11-17')).toEqual([]);
  });

  it('prechod na zimný čas (25. 10. 2026) nemení počet dní', () => {
    expect(addDays('2026-10-24', 1)).toBe('2026-10-25');
    expect(addDays('2026-10-25', 1)).toBe('2026-10-26');
    expect(diffDays('2026-10-24', '2026-10-26')).toBe(2);
    expect(listDays('2026-10-24', '2026-10-27')).toHaveLength(4);
  });

  it('prechod na letný čas (29. 3. 2026) nemení počet dní', () => {
    expect(addDays('2026-03-28', 2)).toBe('2026-03-30');
    expect(diffDays('2026-03-28', '2026-03-30')).toBe(2);
  });

  it('formatDayShort dáva deň týždňa, deň a mesiac', () => {
    expect(formatDayShort('2026-11-16')).toBe('po 16. 11.');
    expect(formatDayShort('2026-11-17')).toBe('ut 17. 11.');
    expect(formatDayShort('2026-12-06')).toBe('ne 6. 12.');
  });

  it('neplatný vstup vyhodí chybu', () => {
    expect(() => addDays('2026-13-01', 1)).toThrow();
    expect(() => addDays('2026-02-30', 1)).toThrow();
    expect(() => addDays('17.11.2026', 1)).toThrow();
  });
});
