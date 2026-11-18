import type { HttpRequest, HttpResponse, LiveEvent, LiveState } from '../platform/types';
import { createWebPlatform } from '../platform/web';
import {
  Connection,
  HEALTH_RETRY_MS,
  HEARTBEAT_TIMEOUT_MS,
  OFFLINE_PROBE_MS,
  type ConnState,
} from './connection';

function setup() {
  const web = createWebPlatform();
  const calls: HttpRequest[] = [];
  const ctl = { health: true, me: 200, netDown: false };
  let onEvent: (e: LiveEvent) => void = () => {};
  let onState: (s: LiveState) => void = () => {};
  const conn = new Connection({
    vault: web.vault,
    http: async (req): Promise<HttpResponse> => {
      calls.push(req);
      if (ctl.netDown) throw { kind: 'network', message: 'down' };
      if (req.path === '/actuator/health') return { status: ctl.health ? 200 : 503, headers: {} };
      return { status: ctl.me, headers: {}, json: {} };
    },
    live: {
      connect: (e, s) => {
        onEvent = e;
        onState = s;
        return () => {};
      },
    },
  });
  const states: ConnState[] = [];
  conn.onTransition((to) => states.push(to));
  return { conn, ctl, calls, states, event: (e: LiveEvent) => onEvent(e), live: (s: LiveState) => onState(s), web };
}

describe('Connection – stavový automat', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('online → checking → offline → online (obnovenie cez /api/me)', async () => {
    const t = setup();
    t.conn.start();
    t.ctl.netDown = true;
    t.live('closed'); // SSE spadlo
    expect(t.conn.getState()).toBe('checking');
    expect(t.conn.canWrite).toBe(false);

    await vi.advanceTimersByTimeAsync(HEALTH_RETRY_MS); // druhý pokus po 5 s
    expect(t.conn.getState()).toBe('offline');
    expect(t.calls.filter((c) => c.path === '/actuator/health')).toHaveLength(2);
    expect(t.calls[0].timeoutMs).toBe(3000);

    // v offline sa kontroluje každých 15 s
    await vi.advanceTimersByTimeAsync(OFFLINE_PROBE_MS);
    expect(t.conn.getState()).toBe('offline');

    t.ctl.netDown = false;
    await vi.advanceTimersByTimeAsync(OFFLINE_PROBE_MS);
    expect(t.conn.getState()).toBe('online');
    expect(t.conn.canWrite).toBe(true);
    expect(t.states).toEqual(['checking', 'offline', 'online']);
    t.conn.stop();
  });

  it('krátky výpadok: úspešná kontrola zdravia vráti stav online', async () => {
    const t = setup();
    t.conn.start();
    t.conn.onNetworkError();
    await vi.advanceTimersByTimeAsync(0);
    expect(t.states).toEqual(['checking', 'online']);
    t.conn.stop();
  });

  it('žiadny heartbeat 40 s → checking', async () => {
    const t = setup();
    t.ctl.netDown = true;
    t.conn.start();
    await vi.advanceTimersByTimeAsync(HEARTBEAT_TIMEOUT_MS - 1);
    expect(t.conn.getState()).toBe('online');
    await vi.advanceTimersByTimeAsync(1);
    expect(t.conn.getState()).toBe('checking');
    t.conn.stop();
  });

  it('heartbeat predĺži čas čakania', async () => {
    const t = setup();
    t.conn.start();
    await vi.advanceTimersByTimeAsync(30_000);
    t.event({ type: 'heartbeat', data: {} });
    await vi.advanceTimersByTimeAsync(30_000);
    expect(t.conn.getState()).toBe('online');
    t.conn.stop();
  });

  it('kontrola v offline uspeje, ale /api/me vráti 401 → session-expired', async () => {
    const t = setup();
    t.conn.start();
    t.ctl.health = false;
    t.live('closed');
    await vi.advanceTimersByTimeAsync(HEALTH_RETRY_MS);
    expect(t.conn.getState()).toBe('offline');
    t.ctl.health = true;
    t.ctl.me = 401;
    await vi.advanceTimersByTimeAsync(OFFLINE_PROBE_MS);
    expect(t.conn.getState()).toBe('session-expired');
    expect(t.conn.canWrite).toBe(false);
    t.conn.stop();
  });

  it('401 z požiadavky → session-expired; po opätovnom prihlásení online', () => {
    const t = setup();
    t.conn.onUnauthorized();
    expect(t.conn.getState()).toBe('session-expired');
    t.conn.onHttpOk(); // v session-expired sa stav sám nemení
    expect(t.conn.getState()).toBe('session-expired');
    t.conn.sessionRestored();
    expect(t.conn.getState()).toBe('online');
  });

  it('nečinnosť podľa idleTimeoutSec zamkne trezor; aktivita lehotu predĺži', async () => {
    const t = setup();
    await t.web.vault.create('jana', 'h');
    const lock = vi.spyOn(t.web.vault, 'lock');
    t.conn.start({ idleTimeoutSec: 60 });
    await vi.advanceTimersByTimeAsync(50_000);
    t.conn.touch();
    await vi.advanceTimersByTimeAsync(50_000);
    expect(t.conn.getState()).toBe('online');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(t.conn.getState()).toBe('locked');
    expect(lock).toHaveBeenCalled();
    // zamknuté: udalosti spojenia stav nemenia
    t.conn.onNetworkError();
    t.conn.onUnauthorized();
    expect(t.conn.getState()).toBe('locked');
    t.conn.stop();
  });

  it('odomknutie: online alebo offline', async () => {
    const t = setup();
    await t.conn.lock();
    t.conn.unlock(true);
    expect(t.conn.getState()).toBe('offline');
    await t.conn.lock();
    t.conn.unlock(false);
    expect(t.conn.getState()).toBe('online');
  });
});
