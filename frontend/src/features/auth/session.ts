import { useSyncExternalStore } from 'react';

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
}

export function clearSessionExpired() {
  if (expired) {
    expired = false;
    emit();
  }
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
