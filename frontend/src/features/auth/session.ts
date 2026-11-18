import { useSyncExternalStore } from 'react';
import { connection } from '../../shared/offline';

// Stav „relácia vypršala“ (401 z ktorejkoľvek požiadavky). Dialóg sa zobrazí nad aktuálnou obrazovkou.
let expired = false;
const listeners = new Set<() => void>();

function emit() {
  listeners.forEach((l) => l());
}

export function markSessionExpired() {
  if (!expired) {
    expired = true;
    emit();
  }
  connection.onUnauthorized();
}

export function clearSessionExpired() {
  if (expired) {
    expired = false;
    emit();
  }
  connection.sessionRestored();
}

export function useSessionExpired(): boolean {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    () => expired,
  );
}
