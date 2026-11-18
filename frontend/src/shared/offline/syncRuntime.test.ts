import type { LiveEvent } from '../platform/types';
import type { ConnState } from './connection';
import type { SyncEngine } from './syncEngine';
import { startSyncRuntime } from './syncRuntime';

function setup(opts: { state?: ConnState; hasData?: boolean; random?: number } = {}) {
  let state: ConnState = opts.state ?? 'online';
  let transition: (to: ConnState, from: ConnState) => void = () => {};
  let live: (e: LiveEvent) => void = () => {};
  const engine: SyncEngine = {
    fullSync: vi.fn(async () => ({ failed: [], finishedAt: '' })),
    syncRooms: vi.fn(async () => {}),
    periodicCheck: vi.fn(async () => {}),
    status: () => ({ running: false, partial: false }),
    subscribe: () => () => {},
    syncIntervalSec: () => 300,
  };
  const stop = startSyncRuntime({
    connection: {
      getState: () => state,
      onTransition: (l) => {
        transition = l;
        return () => {};
      },
      onLiveEvent: (l) => {
        live = l;
        return () => {};
      },
    },
    engine,
    hasData: async () => opts.hasData ?? false,
    random: () => opts.random ?? 0.5,
  });
  return { engine, stop, emit: live, goto: (to: ConnState, from: ConnState) => { state = to; transition(to, from); }, event: (e: LiveEvent) => live(e) };
}

describe('startSyncRuntime', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('štart bez dát v trezore: okamžitá úplná synchronizácia', async () => {
    const t = setup({ hasData: false });
    await vi.advanceTimersByTimeAsync(0);
    expect(t.engine.fullSync).toHaveBeenCalledWith('login');
    t.stop();
  });

  it('štart s dátami: oneskorenie 0–30 s', async () => {
    const t = setup({ hasData: true, random: 0.5 });
    await vi.advanceTimersByTimeAsync(14_999);
    expect(t.engine.fullSync).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(t.engine.fullSync).toHaveBeenCalledWith('login');
    t.stop();
  });

  it('plan-changed → čiastočná synchronizácia izieb', async () => {
    const t = setup();
    t.event({ type: 'plan-changed', data: { receptionId: 2, roomIds: [1, 7], from: '2026-11-20', to: '2026-11-22' } });
    expect(t.engine.syncRooms).toHaveBeenCalledWith(2, [1, 7], '2026-11-20', '2026-11-22');
    t.stop();
  });

  it('business-day-changed → úplná synchronizácia recepcie; heartbeat nič', async () => {
    const t = setup();
    t.event({ type: 'business-day-changed', data: { receptionId: 2, businessDate: '2026-11-19' } });
    expect(t.engine.fullSync).toHaveBeenCalledWith('business-day', { receptionId: 2 });
    t.event({ type: 'heartbeat', data: {} });
    expect(t.engine.syncRooms).not.toHaveBeenCalled();
    t.stop();
  });

  it('po obnovení spojenia (offline → online) úplná synchronizácia', async () => {
    const t = setup({ state: 'offline' });
    expect(t.engine.fullSync).not.toHaveBeenCalled();
    t.goto('online', 'offline');
    await vi.advanceTimersByTimeAsync(0);
    expect(t.engine.fullSync).toHaveBeenCalledWith('reconnect');
    t.stop();
  });

  it('pravidelná kontrola s náhodným posunom ±30 s', async () => {
    const t = setup({ random: 0 }); // posun −30 s → 270 s
    await vi.advanceTimersByTimeAsync(269_000);
    expect(t.engine.periodicCheck).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(t.engine.periodicCheck).toHaveBeenCalledTimes(1);
    t.stop();
  });

  it('po zastavení sa nič nespúšťa', async () => {
    const t = setup({ hasData: true });
    t.stop();
    await vi.advanceTimersByTimeAsync(400_000);
    expect(t.engine.fullSync).not.toHaveBeenCalled();
    expect(t.engine.periodicCheck).not.toHaveBeenCalled();
  });
});
