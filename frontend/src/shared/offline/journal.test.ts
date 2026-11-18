import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { createWebPlatform } from '../platform/web';
import { DS } from './datasets';
import { BATCH_SIZE, flushJournal, recordRead, resetJournalDedup, startJournalRuntime, type JournalRecord, type JournalSpec } from './journal';
import { offlineQuery } from './offlineQuery';

const spec: JournalSpec<{ ids: number[] }> = { operation: 'PLAN_VIEW', dataScope: 'NAME', clientIds: (d) => d.ids };

async function setup() {
  resetJournalDedup();
  const vault = createWebPlatform().vault;
  await vault.create('jana', 'h');
  let t = new Date('2026-11-18T10:00:00Z');
  const deps = { vault, deviceId: async () => 'dev-1', now: () => t };
  return { vault, deps, advance: (ms: number) => (t = new Date(t.getTime() + ms)) };
}

const records = async (vault: ReturnType<typeof createWebPlatform>['vault']) =>
  Promise.all((await vault.list(DS.journal)).map(async (k) => (await vault.get<JournalRecord>(DS.journal, k))!.value));

describe('denník čítaní', () => {
  it('offline zobrazenie zapíše záznam do trezora', async () => {
    const { vault } = await setup();
    await vault.put('plan', '1', { ids: [141, 140, 140] }, { syncedAt: '2026-11-17T10:00:00Z' });
    const conn = { getState: () => 'offline' as const, onNetworkError: vi.fn() };
    const r = await offlineQuery('plan', '1', async () => ({ status: 304 as const }), { vault, connection: conn, now: () => new Date() }, spec);
    expect(r.empty).toBe(false);
    expect(await records(vault)).toMatchObject([
      { operation: 'PLAN_VIEW', dataScope: 'NAME', clientIds: [140, 141], connection: 'OFFLINE', username: 'jana' },
    ]);
  });

  it('pri 304 zapíše CACHED, pri 200 nezapíše nič', async () => {
    const { vault } = await setup();
    await vault.put('plan', '1', { ids: [1] }, { syncedAt: '2026-11-17T10:00:00Z' });
    const conn = { getState: () => 'online' as const, onNetworkError: vi.fn() };
    const deps = { vault, connection: conn, now: () => new Date() };
    await offlineQuery('plan', '1', async () => ({ status: 200 as const, data: { ids: [2] } }), deps, spec);
    expect(await records(vault)).toEqual([]);
    await offlineQuery('plan', '1', async () => ({ status: 304 as const }), deps, spec);
    expect(await records(vault)).toMatchObject([{ connection: 'CACHED', clientIds: [2] }]);
  });

  it('rovnaký pohľad s rovnakými klientmi sa do 5 minút nezapíše znova', async () => {
    const { vault, deps, advance } = await setup();
    await recordRead(spec, { ids: [1, 2] }, 'OFFLINE', deps);
    advance(4 * 60_000);
    await recordRead(spec, { ids: [2, 1] }, 'OFFLINE', deps);
    expect(await vault.list(DS.journal)).toHaveLength(1);
    await recordRead(spec, { ids: [1, 2, 3] }, 'OFFLINE', deps); // iní klienti
    expect(await vault.list(DS.journal)).toHaveLength(2);
    advance(2 * 60_000); // 6 minút od prvého zápisu
    await recordRead(spec, { ids: [1, 2] }, 'OFFLINE', deps);
    expect(await vault.list(DS.journal)).toHaveLength(3);
  });

  it('odošle denník a po 2xx ho zmaže; pri chybe servera ho ponechá a skúsi znova', async () => {
    const { vault, deps, advance } = await setup();
    await recordRead(spec, { ids: [1] }, 'OFFLINE', deps);
    advance(1000);
    await recordRead(spec, { ids: [2] }, 'OFFLINE', deps);
    const bodies: { records: JournalRecord[] }[] = [];
    let fail = true;
    server.use(
      http.post('/api/audit/offline-reads', async ({ request }) => {
        bodies.push((await request.json()) as { records: JournalRecord[] });
        return fail ? HttpResponse.json({}, { status: 500 }) : HttpResponse.json({ accepted: 2 });
      }),
    );
    await flushJournal(deps);
    expect(await vault.list(DS.journal)).toHaveLength(2);
    fail = false;
    await flushJournal(deps);
    expect(bodies).toHaveLength(2);
    expect(bodies[1].records.map((r) => r.clientIds)).toEqual([[1], [2]]);
    expect(await vault.list(DS.journal)).toEqual([]);
  });

  it('dávkuje po max. 500 záznamov', async () => {
    const { vault, deps } = await setup();
    const total = BATCH_SIZE + 5;
    for (let i = 0; i < total; i++) {
      const uid = crypto.randomUUID();
      const rec: JournalRecord = { uid, occurredAt: new Date(Date.UTC(2026, 10, 18, 10, 0, i)).toISOString(), username: 'jana', deviceId: 'd', operation: 'PLAN_VIEW', dataScope: 'NAME', clientIds: [i], connection: 'OFFLINE' };
      await vault.put(DS.journal, uid, rec, { syncedAt: rec.occurredAt });
    }
    const sizes: number[] = [];
    server.use(
      http.post('/api/audit/offline-reads', async ({ request }) => {
        sizes.push(((await request.json()) as { records: unknown[] }).records.length);
        return HttpResponse.json({});
      }),
    );
    await flushJournal(deps);
    expect(sizes).toEqual([BATCH_SIZE, 5]);
    expect(await vault.list(DS.journal)).toEqual([]);
  });

  it('runtime odošle po prechode do online a potom každú minútu', () => {
    vi.useFakeTimers();
    try {
      let state = 'offline';
      let listener: (to: string) => void = () => {};
      const flush = vi.fn(async () => {});
      const stop = startJournalRuntime({
        connection: { getState: () => state as 'online', onTransition: (l) => ((listener = l as never), () => {}) },
        flush,
      });
      expect(flush).not.toHaveBeenCalled();
      state = 'online';
      listener('online');
      expect(flush).toHaveBeenCalledTimes(1);
      vi.advanceTimersByTime(120_000);
      expect(flush).toHaveBeenCalledTimes(3);
      stop();
      vi.advanceTimersByTime(120_000);
      expect(flush).toHaveBeenCalledTimes(3);
    } finally {
      vi.useRealTimers();
    }
  });
});
