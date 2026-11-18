import { platform } from '../platform';
import { connection } from './connection';
import { createSyncEngine } from './syncEngine';

let onDataChanged: ((scope?: 'rooms') => void) | null = null;

/** Aplikácia sem zapojí invalidáciu TanStack Query (krok 7 úplnej synchronizácie). */
export function setDataChangedHandler(handler: ((scope?: 'rooms') => void) | null): void {
  onDataChanged = handler;
}

export const syncEngine = createSyncEngine({
  vault: platform.vault,
  connection,
  onDataChanged: (scope) => onDataChanged?.(scope),
});
