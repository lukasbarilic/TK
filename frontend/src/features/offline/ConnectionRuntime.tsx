import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { queryKeys } from '../../shared/api/queryKeys';
import type { components } from '../../shared/api/schema';
import { platform } from '../../shared/platform';
import {
  connection,
  setDataChangedHandler,
  startSyncRuntime,
  syncEngine,
  useSyncStatus,
} from '../../shared/offline';
import { DS, SESSION_KEYS } from '../../shared/offline/datasets';

const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'wheel', 'touchstart'] as const;

async function readIdleTimeout(): Promise<number | undefined> {
  try {
    const cfg = await platform.vault.get<components['schemas']['ClientConfig']>(DS.session, SESSION_KEYS.clientConfig);
    return cfg?.value.idleTimeoutSec;
  } catch {
    return undefined;
  }
}

/**
 * Spustí sledovanie spojenia, zamykanie po nečinnosti a synchronizáciu, kým je prihlásený používateľ
 * v hlavnej obrazovke (nič nezobrazuje).
 */
export function ConnectionRuntime() {
  const qc = useQueryClient();
  const { lastFullSyncAt } = useSyncStatus();

  useEffect(() => {
    let stopped = false;
    void readIdleTimeout().then((idle) => {
      if (stopped) return;
      connection.start({ idleTimeoutSec: idle });
    });
    const touch = () => connection.touch();
    ACTIVITY_EVENTS.forEach((e) => window.addEventListener(e, touch, { passive: true }));
    setDataChangedHandler((scope) =>
      // po čiastočnej obnove (`plan-changed`) si plán dotknuté izby obnoví sám (features/plan)
      void qc.invalidateQueries(scope === 'rooms' ? { predicate: (q) => q.queryKey[0] !== queryKeys.plan[0] } : undefined),
    );
    const stopSync = startSyncRuntime({
      connection,
      engine: syncEngine,
      hasData: async () => {
        try {
          return Boolean((await platform.vault.info())?.lastFullSyncAt);
        } catch {
          return false;
        }
      },
    });
    return () => {
      stopped = true;
      stopSync();
      setDataChangedHandler(null);
      ACTIVITY_EVENTS.forEach((e) => window.removeEventListener(e, touch));
      connection.stop();
    };
  }, [qc]);

  // `idleTimeoutSec` sa môže po synchronizácii zmeniť
  useEffect(() => {
    void readIdleTimeout().then((idle) => connection.configureIdle(idle));
  }, [lastFullSyncAt]);

  return null;
}
