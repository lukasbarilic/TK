import { createTauriPlatform } from './tauri';

describe('tauri platforma (kostra)', () => {
  it('hlási „nie je implementované“', async () => {
    const p = createTauriPlatform();
    expect(p.kind).toBe('tauri');
    await expect(p.http({ method: 'GET', path: '/api/me' })).rejects.toThrow('nie je implementovaná');
    expect(() => p.live.connect(() => {}, () => {})).toThrow('nie je implementovaná');
    expect(() => p.vault.lock()).toThrow('nie je implementovaná');
  });
});
