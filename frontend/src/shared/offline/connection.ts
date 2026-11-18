import { setApiObserver } from '../api/client';
import { platform } from '../platform';
import type { LiveEvent, LiveState, Platform } from '../platform/types';

/** Stav spojenia podľa DLD kap. 6. */
export type ConnState = 'online' | 'checking' | 'offline' | 'session-expired' | 'locked';

export const HEARTBEAT_TIMEOUT_MS = 40_000;
export const HEALTH_TIMEOUT_MS = 3_000;
export const HEALTH_RETRY_MS = 5_000;
export const HEALTH_ATTEMPTS = 2;
export const OFFLINE_PROBE_MS = 15_000;

type Deps = Pick<Platform, 'http' | 'live' | 'vault'>;
type TransitionListener = (to: ConnState, from: ConnState) => void;

/**
 * Stavový automat spojenia (DLD kap. 6). Časovače používajú globálne `setTimeout`/`Date`,
 * takže v testoch ide nahradiť falošnými hodinami.
 */
export class Connection {
  private state: ConnState = 'online';
  private readonly stateListeners = new Set<() => void>();
  private readonly transitionListeners = new Set<TransitionListener>();
  private readonly liveListeners = new Set<(e: LiveEvent) => void>();

  private running = false;
  private stopLive: (() => void) | null = null;
  private heartbeatTimer: ReturnType<typeof setTimeout> | null = null;
  private probeTimer: ReturnType<typeof setTimeout> | null = null;
  private probeRun = 0;

  private idleMs = 0;
  private lastActivity = 0;
  private idleTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly deps: Deps) {}

  getState = (): ConnState => this.state;
  get canWrite(): boolean {
    return this.state === 'online';
  }

  subscribe = (listener: () => void): (() => void) => {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  };
  onTransition(listener: TransitionListener): () => void {
    this.transitionListeners.add(listener);
    return () => this.transitionListeners.delete(listener);
  }
  onLiveEvent(listener: (e: LiveEvent) => void): () => void {
    this.liveListeners.add(listener);
    return () => this.liveListeners.delete(listener);
  }

  /** Nastaví stav (a spustí/zastaví súvisiace kontroly). */
  setState(next: ConnState): void {
    const prev = this.state;
    if (prev === next) return;
    this.state = next;
    if (next !== 'offline' && next !== 'checking') this.cancelProbe();
    if (next === 'online') this.armHeartbeat();
    if (next === 'locked' || next === 'session-expired') this.clearHeartbeat();
    this.stateListeners.forEach((l) => l());
    this.transitionListeners.forEach((l) => l(next, prev));
    if (next === 'offline' && this.running) this.scheduleProbe(OFFLINE_PROBE_MS);
  }

  /** Natvrdo nastaví stav bez prechodových udalostí (po prihlásení/odhlásení). */
  reset(next: ConnState): void {
    this.cancelProbe();
    const changed = this.state !== next;
    this.state = next;
    if (next === 'online') this.armHeartbeat();
    else this.clearHeartbeat();
    if (changed) this.stateListeners.forEach((l) => l());
    if (next === 'offline' && this.running) this.scheduleProbe(OFFLINE_PROBE_MS);
  }

  // --- spustenie ---

  /** Otvorí SSE, nastaví sledovanie heartbeatu, nečinnosti a pozorovateľa API volaní. */
  start(opts: { idleTimeoutSec?: number } = {}): void {
    if (this.running) return;
    this.running = true;
    setApiObserver({ onResponse: () => this.onHttpOk(), onNetworkError: () => this.onNetworkError() });
    this.stopLive = this.deps.live.connect(
      (e) => this.onLive(e),
      (s) => this.onLiveState(s),
    );
    this.configureIdle(opts.idleTimeoutSec);
    if (this.state === 'online') this.armHeartbeat();
    if (this.state === 'offline') this.scheduleProbe(OFFLINE_PROBE_MS);
    if (this.state === 'checking') void this.check();
  }

  stop(): void {
    if (!this.running) return;
    this.running = false;
    setApiObserver(null);
    this.stopLive?.();
    this.stopLive = null;
    this.clearHeartbeat();
    this.cancelProbe();
    this.clearIdle();
  }

  // --- vstupy automatu ---

  onLiveState(s: LiveState): void {
    if (this.inactive()) return;
    if (s === 'open') {
      if (this.state === 'offline') void this.recover();
      else this.setState('online');
    } else if (s === 'closed') void this.check();
  }

  onLive(e: LiveEvent): void {
    if (this.inactive()) return;
    if (this.state === 'offline') void this.recover();
    else if (this.state === 'checking') this.setState('online');
    else this.armHeartbeat();
    this.liveListeners.forEach((l) => l(e));
  }

  onHttpOk(): void {
    if (this.inactive()) return;
    this.setState('online');
  }

  onNetworkError(): void {
    if (this.inactive()) return;
    void this.check();
  }

  /** 401 z ktorejkoľvek požiadavky. */
  onUnauthorized(): void {
    if (this.state === 'locked') return;
    this.setState('session-expired');
  }

  /** Po opätovnom prihlásení (dialóg „Relácia vypršala“). */
  sessionRestored(): void {
    if (this.state === 'session-expired') this.setState('online');
  }

  private inactive(): boolean {
    return this.state === 'locked' || this.state === 'session-expired';
  }

  // --- kontrola zdravia ---

  /** `checking`: `/actuator/health` (timeout 3 s), 2 pokusy po 5 s; zlyhanie → `offline`. */
  private async check(): Promise<void> {
    if (this.inactive()) return;
    const s = this.getState();
    if (s === 'checking' || s === 'offline') return;
    this.setState('checking');
    this.clearHeartbeat();
    const run = ++this.probeRun;
    for (let attempt = 0; attempt < HEALTH_ATTEMPTS; attempt++) {
      if (attempt > 0) await this.sleep(HEALTH_RETRY_MS);
      if (run !== this.probeRun || this.getState() !== 'checking') return;
      if (await this.healthy()) {
        if (run === this.probeRun && this.getState() === 'checking') this.setState('online');
        return;
      }
    }
    if (run === this.probeRun && this.getState() === 'checking') this.setState('offline');
  }

  private async healthy(): Promise<boolean> {
    try {
      const r = await this.deps.http({ method: 'GET', path: '/actuator/health', timeoutMs: HEALTH_TIMEOUT_MS });
      return r.status >= 200 && r.status < 300;
    } catch {
      return false;
    }
  }

  private sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private scheduleProbe(ms: number): void {
    this.cancelProbe();
    const run = this.probeRun;
    this.probeTimer = setTimeout(() => {
      this.probeTimer = null;
      if (run !== this.probeRun || this.state !== 'offline') return;
      void this.probeOffline();
    }, ms);
  }

  private async probeOffline(): Promise<void> {
    if (await this.healthy()) await this.recover();
    else if (this.state === 'offline' && this.running) this.scheduleProbe(OFFLINE_PROBE_MS);
  }

  /** Server odpovedá: `GET /api/me` rozhodne medzi `online` a `session-expired`. */
  private async recover(): Promise<void> {
    if (this.state !== 'offline') return;
    let status: number;
    try {
      status = (await this.deps.http({ method: 'GET', path: '/api/me' })).status;
    } catch {
      if (this.getState() === 'offline' && this.running) this.scheduleProbe(OFFLINE_PROBE_MS);
      return;
    }
    if (this.getState() !== 'offline') return;
    if (status === 401) this.setState('session-expired');
    else if (status >= 200 && status < 300) this.setState('online');
    else if (this.running) this.scheduleProbe(OFFLINE_PROBE_MS);
  }

  private cancelProbe(): void {
    this.probeRun++;
    if (this.probeTimer) clearTimeout(this.probeTimer);
    this.probeTimer = null;
  }

  // --- heartbeat ---

  private armHeartbeat(): void {
    this.clearHeartbeat();
    if (!this.running || this.state !== 'online') return;
    this.heartbeatTimer = setTimeout(() => {
      this.heartbeatTimer = null;
      void this.check();
    }, HEARTBEAT_TIMEOUT_MS);
  }

  private clearHeartbeat(): void {
    if (this.heartbeatTimer) clearTimeout(this.heartbeatTimer);
    this.heartbeatTimer = null;
  }

  // --- zamknutie po nečinnosti ---

  /** `idleTimeoutSec` z `client-config`; 0/nedefinované = bez zamykania. */
  configureIdle(idleTimeoutSec: number | undefined): void {
    this.clearIdle();
    this.idleMs = idleTimeoutSec && idleTimeoutSec > 0 ? idleTimeoutSec * 1000 : 0;
    if (this.idleMs > 0 && this.running) {
      this.lastActivity = Date.now();
      this.armIdle(this.idleMs);
    }
  }

  /** Aktivita používateľa (myš, klávesnica). */
  touch(): void {
    this.lastActivity = Date.now();
  }

  private armIdle(ms: number): void {
    this.idleTimer = setTimeout(() => {
      this.idleTimer = null;
      const left = this.idleMs - (Date.now() - this.lastActivity);
      if (left > 0) this.armIdle(left);
      else void this.lock();
    }, ms);
  }

  private clearIdle(): void {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = null;
  }

  /** Zamkne trezor a prepne na obrazovku zamknutia. */
  async lock(): Promise<void> {
    if (this.state === 'locked') return;
    this.clearIdle();
    this.setState('locked');
    try {
      await this.deps.vault.lock();
    } catch {
      // trezor už mohol byť zamknutý
    }
  }

  /** Po úspešnom odomknutí: online heslom na serveri, inak (offline) heslom trezora. */
  unlock(offline: boolean): void {
    if (this.state !== 'locked') return;
    this.state = offline ? 'offline' : 'online';
    this.stateListeners.forEach((l) => l());
    this.transitionListeners.forEach((l) => l(this.state, 'locked'));
    if (offline) {
      if (this.running) this.scheduleProbe(OFFLINE_PROBE_MS);
    } else this.armHeartbeat();
    this.configureIdle(this.idleMs / 1000);
  }
}

export const connection = new Connection(platform);
