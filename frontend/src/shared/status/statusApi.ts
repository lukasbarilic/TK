import { apiCall } from '../api/client';
import type { components } from '../api/schema';

export type StatusDetail = components['schemas']['Detail'];

/** Akcie nad stavom rezervačnej karty (B08). */
export type StatusAction = 'checkIn' | 'checkOut' | 'cancel' | 'noShow' | 'undoCheckIn' | 'revertNoShow';

export interface StatusOptions {
  /** Potvrdené upozornenia (pri odchode skôr: `EARLY_DEPARTURE`). */
  confirm?: string[];
  /** Dôvod storna (povinný pri `cancel`). */
  reason?: string;
}

/**
 * Vykoná akciu na karte. Verzia (`version`, optimistic locking) sa vždy načíta čerstvá, lebo pásik plánu ani riadok
 * príchodov ju nemajú; ak ju medzitým zmenil niekto iný, server vráti konflikt.
 */
export async function runStatusAction(action: StatusAction, id: number, o: StatusOptions = {}): Promise<StatusDetail> {
  const { version } = (await apiCall('get', '/api/reservations/{id}', { path: { id } })).data;
  if (version === undefined) throw new Error(`Karta ${id} nemá verziu`);
  const path = { id };
  switch (action) {
    case 'checkIn':
      return (await apiCall('post', '/api/reservations/{id}/check-in', { path, body: { version } })).data;
    case 'checkOut':
      return (await apiCall('post', '/api/reservations/{id}/check-out', { path, body: { version, ...(o.confirm?.length ? { confirm: o.confirm } : {}) } })).data;
    case 'cancel':
      return (await apiCall('post', '/api/reservations/{id}/cancel', { path, body: { version, reason: o.reason ?? '' } })).data;
    case 'noShow':
      return (await apiCall('post', '/api/reservations/{id}/no-show', { path, body: { version } })).data;
    case 'undoCheckIn':
      return (await apiCall('post', '/api/reservations/{id}/undo-check-in', { path, body: { version } })).data;
    case 'revertNoShow':
      return (await apiCall('post', '/api/reservations/{id}/revert-no-show', { path, body: { version } })).data;
  }
}
