import { platform } from '../platform';
import { connection } from './connection';
import { createSyncEngine } from './syncEngine';

let onDataChanged: (() => void) | null = null;

/** Aplikácia sem zapojí invalidáciu TanStack Query (krok 7 úplnej synchronizácie). */
export function setDataChangedHandler(handler: (() => void) | null): void {
  onDataChanged = handler;
}

export const syncEngine = createSyncEngine({
  vault: platform.vault,
  connection,
  onDataChanged: () => onDataChanged?.(),
});
