import { useEffect, useState, useSyncExternalStore } from 'react';
import { platform } from '../platform';
import { connection, type ConnState } from './connection';
import { syncEngine } from './engine';
import type { SyncStatus } from './syncEngine';

/** Stav spojenia; zápis je povolený len v stave `online` (DLD kap. 6). */
export function useConnection(): { state: ConnState; canWrite: boolean } {
  const state = useSyncExternalStore(connection.subscribe, connection.getState);
  return { state, canWrite: state === 'online' };
}

export function useSyncStatus(): SyncStatus {
  return useSyncExternalStore(syncEngine.subscribe, syncEngine.status);
}

/** Čas posledného úplného obnovenia dát z trezora (pre „Údaje z …“). */
export function useLastSync(): string | undefined {
  const status = useSyncStatus();
  const [fromVault, setFromVault] = useState<string>();
  useEffect(() => {
    let cancelled = false;
    platform.vault
      .info()
      .then((i) => !cancelled && setFromVault(i?.lastFullSyncAt))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [status.lastFullSyncAt]);
  return status.lastFullSyncAt ?? fromVault;
}
