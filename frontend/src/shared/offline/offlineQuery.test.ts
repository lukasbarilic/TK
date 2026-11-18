import { createWebPlatform } from '../platform/web';
import { offlineQuery, type FetchResult } from './offlineQuery';

const NOW = new Date('2026-11-18T10:00:00Z');

async function setup(state: 'online' | 'offline' | 'checking' | 'session-expired' = 'online') {
  const vault = createWebPlatform().vault;
  await vault.create('jana', 'h');
  const onNetworkError = vi.fn();
  const deps = { vault, connection: { getState: () => state, onNetworkError }, now: () => NOW };
  return { vault, onNetworkError, deps };
}

describe('offlineQuery', () => {
  it('online 200: uloží do trezora vrátane ETagu a vráti source=server', async () => {
    const { vault, deps } = await setup();
    const r = await offlineQuery<{ a: number }>('ds', 'k', async () => ({ status: 200, data: { a: 1 }, etag: '"e1"' }), deps);
    expect(r).toEqual({ data: { a: 1 }, source: 'server', syncedAt: NOW.toISOString(), empty: false });
    expect(await vault.get('ds', 'k')).toEqual({ value: { a: 1 }, meta: { etag: '"e1"', syncedAt: NOW.toISOString() } });
  });

  it('online 304: pošle uložený ETag a vráti dáta z trezoru', async () => {
    const { vault, deps } = await setup();
    await vault.put('ds', 'k', { a: 1 }, { etag: '"e1"', syncedAt: '2026-11-17T10:00:00Z' });
    const fetcher = vi.fn(async (): Promise<FetchResult<{ a: number }>> => ({ status: 304 }));
    const r = await offlineQuery('ds', 'k', fetcher, deps);
    expect(fetcher).toHaveBeenCalledWith('"e1"');
    expect(r).toMatchObject({ data: { a: 1 }, source: 'cache', syncedAt: NOW.toISOString() });
    expect((await vault.get('ds', 'k'))?.meta.etag).toBe('"e1"');
  });

  it('sieťová chyba: prepne na kontrolu spojenia a vráti dáta z trezora', async () => {
    const { vault, deps, onNetworkError } = await setup();
    await vault.put('ds', 'k', { a: 1 }, { syncedAt: '2026-11-17T10:00:00Z' });
    const r = await offlineQuery('ds', 'k', async () => {
      throw { kind: 'network', message: 'x' };
    }, deps);
    expect(onNetworkError).toHaveBeenCalled();
    expect(r).toMatchObject({ data: { a: 1 }, source: 'cache', syncedAt: '2026-11-17T10:00:00Z' });
  });

  it.each(['offline', 'session-expired', 'checking'] as const)('%s: server sa nevolá, dáta z trezora', async (state) => {
    const { vault, deps } = await setup(state);
    await vault.put('ds', 'k', { a: 1 }, { syncedAt: '2026-11-17T10:00:00Z' });
    const fetcher = vi.fn();
    const r = await offlineQuery('ds', 'k', fetcher, deps);
    expect(fetcher).not.toHaveBeenCalled();
    expect(r).toMatchObject({ data: { a: 1 }, source: 'cache', syncedAt: '2026-11-17T10:00:00Z', empty: false });
  });

  it('offline a v trezore nič nie je → prázdny stav „nie sú uložené“', async () => {
    const { deps } = await setup('offline');
    expect(await offlineQuery('ds', 'chyba', vi.fn(), deps)).toEqual({
      data: null,
      source: 'cache',
      syncedAt: undefined,
      empty: true,
    });
  });

  it('chyba servera (nie sieťová) sa vyhodí ďalej', async () => {
    const { deps } = await setup();
    await expect(
      offlineQuery('ds', 'k', async () => {
        throw new Error('boom');
      }, deps),
    ).rejects.toThrow('boom');
  });
});
