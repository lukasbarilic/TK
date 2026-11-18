import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';
import { offlineQuery, type JournalSpec } from '../../shared/offline';
import { DS, movementsKey } from '../../shared/offline/datasets';

export type Movements = components['schemas']['Movements'];
export type Movement = components['schemas']['Movement'];
type ReceptionView = components['schemas']['ReceptionView'];
type DayView = components['schemas']['DayView'];

export const loadReceptions = () =>
  offlineQuery<ReceptionView[]>(DS.catalog, 'receptions', async (etag) => {
    const res = await apiCall('get', '/api/catalog/receptions', { headers: etag ? { 'If-None-Match': etag } : {} });
    return res.status === 304 ? { status: 304 } : { status: 200, data: res.data, etag: res.headers.etag };
  });

export const loadBusinessDay = (receptionId: number) =>
  offlineQuery<DayView>(DS.businessDay, String(receptionId), async () => ({
    status: 200,
    data: (await apiCall('get', '/api/receptions/{id}/business-day', { path: { id: receptionId } })).data,
  }));

/** Denník čítaní (DLD kap. 7): klienti = ID kariet príchodov a odchodov. */
const movementsJournal: JournalSpec<Movements> = {
  operation: 'MOVEMENTS_VIEW',
  dataScope: 'NAME',
  clientIds: (m) => [...(m.arrivals ?? []), ...(m.departures ?? [])].flatMap((x) => (x.reservationId === undefined ? [] : [x.reservationId])),
};

/** Príchody a odchody (B02): online zo servera, offline z trezora (`movements/<receptionId>:<date>`). */
export const loadMovements = (receptionId: number, date: string) =>
  offlineQuery<Movements>(DS.movements, movementsKey(receptionId, date), async (etag) => {
    const res = await apiCall('get', '/api/receptions/{id}/movements', {
      path: { id: receptionId },
      query: { date },
      headers: etag ? { 'If-None-Match': etag } : {},
    });
    return res.status === 304 ? { status: 304 } : { status: 200, data: res.data, etag: res.headers.etag };
  }, undefined, movementsJournal);

/** Triedenie podľa izby (bez izby hore, číselne) a potom podľa mena. */
export function sortMovements(list: Movement[] | undefined): Movement[] {
  const cmp = (a?: string, b?: string) => (a ?? '').localeCompare(b ?? '', 'sk', { numeric: true, sensitivity: 'base' });
  return [...(list ?? [])].sort((a, b) => {
    if (!a.roomNumber !== !b.roomNumber) return a.roomNumber ? 1 : -1;
    return cmp(a.roomNumber, b.roomNumber) || (a.bedNumber ?? 0) - (b.bedNumber ?? 0) || cmp(a.guestName, b.guestName);
  });
}
