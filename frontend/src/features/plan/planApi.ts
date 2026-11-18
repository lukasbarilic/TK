import { apiCall, type ApiResult } from '../../shared/api/client';
import { ApiError } from '../../shared/api/errors';
import type { components } from '../../shared/api/schema';
import { putLocal, offlineQuery, reservationViewSpec, type JournalSpec, type FetchResult, type OfflineResult } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { addDays } from '../../shared/time';
import { DEFAULT_VIEW, type DefaultView, type Plan, type StoredPlan } from './types';

export type Preference = components['schemas']['Preference'];
type ClientConfig = components['schemas']['ClientConfig'];
type DayView = components['schemas']['DayView'];
type ReceptionView = components['schemas']['ReceptionView'];
type Detail = components['schemas']['Detail'];

/** Pre `offlineQuery`: volanie s uloženým ETagom; `304` → dáta ostávajú v trezore. */
async function withEtag<T>(call: (headers: Record<string, string>) => Promise<ApiResult<T>>, etag?: string): Promise<FetchResult<T>> {
  const res = await call(etag ? { 'If-None-Match': etag } : {});
  return res.status === 304 ? { status: 304 } : { status: 200, data: res.data, etag: res.headers.etag };
}

export const loadReceptions = () =>
  offlineQuery<ReceptionView[]>(DS.catalog, 'receptions', (etag) =>
    withEtag((headers) => apiCall('get', '/api/catalog/receptions', { headers }), etag),
  );

export const loadClientConfig = () =>
  offlineQuery<ClientConfig>(DS.session, 'client-config', (etag) =>
    withEtag((headers) => apiCall('get', '/api/client-config', { headers }), etag),
  );

export const loadBusinessDay = (receptionId: number) =>
  offlineQuery<DayView>(DS.businessDay, String(receptionId), async () => ({
    status: 200,
    data: (await apiCall('get', '/api/receptions/{id}/business-day', { path: { id: receptionId } })).data,
  }));

/** Okno synchronizácie (DLD kap. 5.2): `businessDate − planDaysBack … + planDaysAhead`. */
export function syncWindow(businessDate: string, config: ClientConfig | null | undefined): { from: string; to: string } {
  return {
    from: addDays(businessDate, -(config?.offline?.planDaysBack ?? 1)),
    to: addDays(businessDate, config?.offline?.planDaysAhead ?? 14),
  };
}

/** Denník čítaní (DLD kap. 7): klienti = ID kariet z pásikov pobytov v pláne. */
const planJournal: JournalSpec<StoredPlan> = {
  operation: 'PLAN_VIEW',
  dataScope: 'NAME',
  clientIds: (p) =>
    (p.plan.rooms ?? []).flatMap((r) =>
      (r.bars ?? []).flatMap((b) => ('reservationId' in b && typeof b.reservationId === 'number' ? [b.reservationId] : [])),
    ),
};

/**
 * Plán pre zobrazený rozsah. Okno synchronizácie ide cez `offlineQuery` (zdieľa trezor aj ETag so synchronizáciou);
 * rozsah mimo okna sa dotiahne len online, bez zápisu do trezora.
 */
export async function loadPlan(
  receptionId: number,
  view: { from: string; to: string },
  win: { from: string; to: string },
  online: boolean,
): Promise<OfflineResult<StoredPlan>> {
  const from = view.from < win.from ? view.from : win.from;
  const to = view.to > win.to ? view.to : win.to;
  if (online && (from !== win.from || to !== win.to)) {
    const plan = (await apiCall('get', '/api/accommodation-plan', { query: { receptionId, from, to } })).data;
    return { data: { from, to, plan }, source: 'server', syncedAt: new Date().toISOString(), empty: false };
  }
  return offlineQuery<StoredPlan>(DS.plan, String(receptionId), async (etag) => {
    const res = await apiCall('get', '/api/accommodation-plan', {
      query: { receptionId, from: win.from, to: win.to },
      headers: etag ? { 'If-None-Match': etag } : {},
    });
    if (res.status === 304) return { status: 304 };
    return { status: 200, data: { from: win.from, to: win.to, plan: res.data }, etag: res.headers.etag };
  }, undefined, planJournal);
}

/** Len dotknuté izby (živá zmena, DLD kap. 5.3 / HLD kap. 6.4). */
export async function loadRooms(receptionId: number, roomIds: number[], from: string, to: string): Promise<Plan> {
  return (await apiCall('get', '/api/accommodation-plan', { query: { receptionId, roomIds, from, to } })).data;
}

/** Karta rezervácie pre bočný panel: online zo servera, offline z trezora (dataset `reservation`, B03). */
export const loadCard = (reservationId: number) =>
  offlineQuery<Detail>(DS.reservation, String(reservationId), async () => ({
    status: 200,
    data: (await apiCall('get', '/api/reservations/{id}', { path: { id: reservationId } })).data,
  }), undefined, reservationViewSpec);

/** Nastavenie používateľa (B06); neexistujúce (404) = predvolené. */
export const loadPreference = (key: string) =>
  offlineQuery<Preference>(DS.preferences, key, async () => {
    try {
      return { status: 200, data: (await apiCall('get', '/api/me/preferences/{key}', { path: { key } })).data };
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) return { status: 200, data: { key } };
      throw e;
    }
  });

/** Uloží nastavenie: vždy lokálne, na server len online (konflikt verzie = jeden opakovaný pokus s čerstvou verziou). */
export async function savePreference(key: string, value: unknown, version: number | undefined, online: boolean): Promise<Preference> {
  let saved: Preference = { key, value: value as Preference['value'], version };
  if (online) {
    const put = (v: number | undefined) =>
      apiCall('put', '/api/me/preferences/{key}', { path: { key }, body: { value: value as Preference['value'], version: v } });
    try {
      saved = (await put(version)).data;
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 409)) throw e;
      const fresh = (await apiCall('get', '/api/me/preferences/{key}', { path: { key } })).data;
      saved = (await put(fresh.version)).data;
    }
  }
  await putLocal(DS.preferences, key, saved);
  return saved;
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;
const int = (v: unknown, d: number) => (typeof v === 'number' && Number.isInteger(v) ? v : d);

/** `plan.defaultView` s ošetrením neplatných hodnôt. */
export function parseDefaultView(raw: unknown): DefaultView {
  if (!isRecord(raw)) return DEFAULT_VIEW;
  return {
    receptionId: typeof raw.receptionId === 'number' ? raw.receptionId : undefined,
    buildingIds: Array.isArray(raw.buildingIds) ? raw.buildingIds.filter((x): x is number => typeof x === 'number') : [],
    daysBack: Math.min(31, Math.max(0, int(raw.daysBack, DEFAULT_VIEW.daysBack))),
    daysAhead: Math.min(60, Math.max(0, int(raw.daysAhead, DEFAULT_VIEW.daysAhead))),
    freeOnly: raw.freeOnly === true,
    fullyFree: raw.fullyFree === true,
    zoom: raw.zoom === 'narrow' || raw.zoom === 'wide' ? raw.zoom : 'normal',
  };
}
