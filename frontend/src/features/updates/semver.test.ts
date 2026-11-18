import { compareVersions, isBelowMinimum } from './semver';

describe('semver', () => {
  it('porovnáva číselne, nie textovo', () => {
    expect(compareVersions('0.9.0', '0.10.0')).toBeLessThan(0);
    expect(compareVersions('1.2.3', '1.2.3')).toBe(0);
    expect(compareVersions('2.0.0', '1.99.99')).toBeGreaterThan(0);
  });
  it('predvydanie je nižšie ako vydanie; build sa ignoruje', () => {
    expect(compareVersions('1.0.0-rc.1', '1.0.0')).toBeLessThan(0);
    expect(compareVersions('1.0.0-rc.2', '1.0.0-rc.10')).toBeLessThan(0);
    expect(compareVersions('1.0.0+b5', '1.0.0')).toBe(0);
  });
  it('neplatná verzia vyhodí chybu', () => {
    expect(() => compareVersions('abc', '1.0.0')).toThrow();
  });
  it('isBelowMinimum: nižšia blokuje, chýbajúca alebo neplatná nie', () => {
    expect(isBelowMinimum('0.1.0', '0.2.0')).toBe(true);
    expect(isBelowMinimum('0.2.0', '0.2.0')).toBe(false);
    expect(isBelowMinimum('0.1.0', undefined)).toBe(false);
    expect(isBelowMinimum('0.1.0', 'x')).toBe(false);
  });
});
