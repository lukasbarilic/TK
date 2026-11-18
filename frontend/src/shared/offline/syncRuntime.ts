import type { LiveEvent } from '../platform/types';
import type { Connection } from './connection';
import type { SyncEngine } from './syncEngine';

const START_DELAY_MAX_MS = 30_000;
const PERIODIC_JITTER_MS = 30_000;

interface Options {
  connection: Pick<Connection, 'onTransition' | 'onLiveEvent' | 'getState'>;
  engine: SyncEngine;
  /** Má trezor už nejaké dáta? Vtedy sa štart oneskorí o 0–30 s (DLD kap. 5.3). */
  hasData: () => Promise<boolean>;
  random?: () => number;
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;
const numbers = (v: unknown): number[] => (Array.isArray(v) ? v.filter((x): x is number => typeof x === 'number') : []);

/** Spája spojenie, živé udalosti a pravidelnú kontrolu so `SyncEngine` (DLD kap. 5.3). Vracia funkciu na zastavenie. */
export function startSyncRuntime({ connection, engine, hasData, random = Math.random }: Options): () => void {
  let stopped = false;
  const timers = new Set<ReturnType<typeof setTimeout>>();
  const later = (ms: number, fn: () => void) => {
    const t = setTimeout(() => {
      timers.delete(t);
      if (!stopped) fn();
    }, ms);
    timers.add(t);
  };

  /** Štart po prihlásení / reconnecte: s dátami v trezore po náhodnom oneskorení, inak hneď. */
  const scheduleFull = (reason: 'login' | 'reconnect') => {
    void hasData().then((has) => {
      if (stopped) return;
      const run = () => {
        if (connection.getState() === 'online') void engine.fullSync(reason);
      };
      if (has) later(Math.floor(random() * START_DELAY_MAX_MS), run);
      else run();
    });
  };

  const onEvent = (e: LiveEvent) => {
    if (!isRecord(e.data)) return;
    if (e.type === 'plan-changed') {
      const { receptionId, from, to } = e.data;
      const roomIds = numbers(e.data.roomIds);
      if (typeof receptionId === 'number' && typeof from === 'string' && typeof to === 'string') {
        void engine.syncRooms(receptionId, roomIds, from, to).catch(() => {});
      }
    } else if (e.type === 'business-day-changed' && typeof e.data.receptionId === 'number') {
      void engine.fullSync('business-day', { receptionId: e.data.receptionId });
    }
  };

  const unsubEvents = connection.onLiveEvent(onEvent);
  const unsubTransition = connection.onTransition((to, from) => {
    if (to === 'online' && (from === 'offline' || from === 'session-expired')) {
      scheduleFull('reconnect');
    }
  });

  const schedulePeriodic = () => {
    const base = engine.syncIntervalSec() * 1000;
    const jitter = (random() * 2 - 1) * PERIODIC_JITTER_MS;
    later(Math.max(1000, base + jitter), () => {
      void engine.periodicCheck().catch(() => {});
      schedulePeriodic();
    });
  };

  if (connection.getState() === 'online') scheduleFull('login');
  schedulePeriodic();

  return () => {
    stopped = true;
    unsubEvents();
    unsubTransition();
    timers.forEach(clearTimeout);
    timers.clear();
  };
}
