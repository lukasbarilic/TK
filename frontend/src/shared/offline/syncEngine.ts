import { apiCall, type ApiResult } from '../api/client';
import { ApiError, isNetError } from '../api/errors';
import type { components } from '../api/schema';
import type { Platform } from '../platform/types';
import { addDays } from '../time';
import type { Connection } from './connection';
import { CATALOGS, DS, SESSION_KEYS, movementsKey } from './datasets';
import { hasPermissionCode, receptionsInScope } from './scope';

type Me = components['schemas']['MeView'];
type ClientConfig = components['schemas']['ClientConfig'];
type Plan = components['schemas']['Plan'];
type Movements = components['schemas']['Movements'];
type DayView = components['schemas']['DayView'];
type ReceptionView = components['schemas']['ReceptionView'];

export type SyncReason = 'login' | 'reconnect' | 'business-day' | 'manual' | 'periodic';

export interface SyncReport {
  /** Názvy sád (`dataset/key`), ktoré sa nepodarilo obnoviť. */
  failed: string[];
  /** Synchronizácia sa predčasne skončila (401 alebo strata spojenia). */
  aborted?: 'session-expired' | 'offline';
  finishedAt: string;
}

export interface SyncStatus {
  lastFullSyncAt?: string;
  running: boolean;
  lastError?: string;
  /** Posledná synchronizácia sa nepodarila celá („Časť údajov sa nepodarilo obnoviť“). */
  partial: boolean;
}

export interface SyncEngine {
  fullSync(reason: SyncReason, opts?: { receptionId?: number }): Promise<SyncReport>;
  syncRooms(receptionId: number, roomIds: number[], from: string, to: string): Promise<void>;
  periodicCheck(): Promise<void>;
  status(): SyncStatus;
  subscribe(listener: () => void): () => void;
  /** `offline.syncIntervalSec` z poslednej známej konfigurácie. */
  syncIntervalSec(): number;
}

export interface SyncEngineOptions {
  vault: Platform['vault'];
  connection: Pick<Connection, 'getState' | 'onUnauthorized'>;
  /** Volá sa po úspešnom obnovení dát (TanStack Query: invalidácia všetkých dotazov). */
  onDataChanged?: () => void;
  now?: () => Date;
  concurrency?: number;
}

const DEFAULT_CONFIG = { planDaysBack: 1, planDaysAhead: 14, syncIntervalSec: 300 } as const;

interface StoredPlan {
  from: string;
  to: string;
  plan: Plan;
}
interface StoredCards {
  ids: number[];
}
type Offline = NonNullable<ClientConfig['offline']>;
type Detail = components['schemas']['Detail'];

class Abort extends Error {
  constructor(readonly reason: 'session-expired' | 'offline') {
    super(reason);
  }
}

/** ID rezervácií z pásikov plánu (pásiky sú zjednotenie typov, preto sa číta voľne). */
function reservationIdsOf(plan: Plan | undefined, roomIds?: number[]): number[] {
  const ids = new Set<number>();
  for (const room of plan?.rooms ?? []) {
    if (roomIds && (room.roomId === undefined || !roomIds.includes(room.roomId))) continue;
    for (const bar of room.bars ?? []) {
      const id = (bar as { reservationId?: number }).reservationId;
      if (typeof id === 'number') ids.add(id);
    }
  }
  return [...ids];
}

export function createSyncEngine(opts: SyncEngineOptions): SyncEngine {
  const { vault, connection } = opts;
  const now = opts.now ?? (() => new Date());
  const limit = opts.concurrency ?? 3;
  const listeners = new Set<() => void>();
  let status: SyncStatus = { running: false, partial: false };
  let current: Promise<SyncReport> | null = null;
  let config: Offline = { ...DEFAULT_CONFIG };
  let serverTime: string | undefined;

  const setStatus = (patch: Partial<SyncStatus>) => {
    status = { ...status, ...patch };
    listeners.forEach((l) => l());
  };

  /** Volanie s ETagom: 200 → uloží, 304 → obsah ostáva, aktualizuje sa len `syncedAt`. */
  async function cached<T>(
    ds: string,
    key: string,
    call: (headers: Record<string, string>) => Promise<ApiResult<T>>,
    wrap: (data: T) => unknown = (d) => d,
  ): Promise<void> {
    const stored = await vault.get<unknown>(ds, key);
    const headers: Record<string, string> = stored?.meta.etag ? { 'If-None-Match': stored.meta.etag } : {};
    const res = await call(headers);
    const syncedAt = now().toISOString();
    if (res.status === 304 && stored) {
      await vault.put(ds, key, stored.value, { etag: stored.meta.etag, syncedAt });
      return;
    }
    await vault.put(ds, key, wrap(res.data), { etag: res.headers.etag, syncedAt });
  }

  /** Spustí úlohy s obmedzenou súbežnosťou; chyba jednej sady nezastaví ostatné. */
  async function runAll(
    tasks: { name: string; run: () => Promise<void> }[],
    report: SyncReport,
    guard: { abort: Abort | null },
  ): Promise<void> {
    let next = 0;
    const worker = async () => {
      while (next < tasks.length && !guard.abort) {
        const t = tasks[next++];
        try {
          await t.run();
        } catch (e) {
          if (e instanceof ApiError && e.status === 401) {
            connection.onUnauthorized();
            guard.abort ??= new Abort('session-expired');
          } else if (isNetError(e)) guard.abort ??= new Abort('offline');
          report.failed.push(t.name);
          setStatus({ lastError: e instanceof Error ? e.message : String((e as { message?: string }).message ?? e) });
        }
      }
    };
    await Promise.all(Array.from({ length: Math.min(limit, tasks.length) }, worker));
  }

  const windowOf = (businessDate: string) => ({
    from: addDays(businessDate, -(config.planDaysBack ?? DEFAULT_CONFIG.planDaysBack)),
    to: addDays(businessDate, config.planDaysAhead ?? DEFAULT_CONFIG.planDaysAhead),
  });

  const fetchMovements = (receptionId: number, date: string) =>
    cached(
      DS.movements,
      movementsKey(receptionId, date),
      (headers) => apiCall('get', '/api/receptions/{id}/movements', { path: { id: receptionId }, query: { date }, headers }),
    );

  /** Hromadné karty B03 (`from`/`to` alebo `ids`); uloží každú kartu zvlášť. */
  async function storeCards(details: Detail[]): Promise<void> {
    const syncedAt = now().toISOString();
    for (const d of details) {
      if (d.id !== undefined) await vault.put(DS.reservation, String(d.id), d, { syncedAt });
    }
  }

  async function syncCards(receptionId: number, from: string, to: string): Promise<void> {
    const key = String(receptionId);
    const stored = await vault.get<StoredCards>(DS.reservationSync, key);
    const headers: Record<string, string> = stored?.meta.etag ? { 'If-None-Match': stored.meta.etag } : {};
    const res = await apiCall('get', '/api/offline/reservations', { query: { receptionId, from, to }, headers });
    const syncedAt = now().toISOString();
    if (res.status === 304 && stored) {
      await vault.put(DS.reservationSync, key, stored.value, { etag: stored.meta.etag, syncedAt });
      return;
    }
    const details = res.data ?? [];
    await storeCards(details);
    const ids = details.flatMap((d) => (d.id === undefined ? [] : [d.id]));
    await vault.put(DS.reservationSync, key, { ids } satisfies StoredCards, { etag: res.headers.etag, syncedAt });
  }

  async function currentMe(): Promise<Me | undefined> {
    return (await vault.get<Me>(DS.session, SESSION_KEYS.me))?.value;
  }

  async function doFullSync(opt: { receptionId?: number }): Promise<SyncReport> {
    const report: SyncReport = { failed: [], finishedAt: '' };
    const guard: { abort: Abort | null } = { abort: null };
    const finish = (): SyncReport => {
      report.finishedAt = now().toISOString();
      if (guard.abort) report.aborted = guard.abort.reason;
      return report;
    };

    // 1. client-config a me (pri 401 koniec)
    let me: Me | undefined;
    try {
      const cfg = (await apiCall('get', '/api/client-config')).data;
      config = { ...DEFAULT_CONFIG, ...cfg.offline };
      serverTime = cfg.serverTime;
      await vault.put(DS.session, SESSION_KEYS.clientConfig, cfg, { syncedAt: now().toISOString() });
      me = (await apiCall('get', '/api/me')).data;
      await vault.put(DS.session, SESSION_KEYS.me, me, { syncedAt: now().toISOString() });
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        connection.onUnauthorized();
        guard.abort = new Abort('session-expired');
      } else if (isNetError(e)) guard.abort = new Abort('offline');
      report.failed.push(DS.session);
      setStatus({ lastError: e instanceof Error ? e.message : DS.session });
      if (guard.abort) return finish();
      me = await currentMe();
      const storedCfg = await vault.get<ClientConfig>(DS.session, SESSION_KEYS.clientConfig);
      if (storedCfg) config = { ...DEFAULT_CONFIG, ...storedCfg.value.offline };
    }

    // 2. číselníky (s ETag)
    const catalogCalls: Record<(typeof CATALOGS)[number], (h: Record<string, string>) => Promise<ApiResult<unknown>>> = {
      centres: (headers) => apiCall('get', '/api/catalog/centres', { headers }),
      receptions: (headers) => apiCall('get', '/api/catalog/receptions', { headers }),
      buildings: (headers) => apiCall('get', '/api/catalog/buildings', { headers }),
      rooms: (headers) => apiCall('get', '/api/catalog/rooms', { query: {}, headers }),
      'room-categories': (headers) => apiCall('get', '/api/catalog/room-categories', { headers }),
      payers: (headers) => apiCall('get', '/api/catalog/payers', { headers }),
    };
    await runAll(
      CATALOGS.map((key) => ({ name: `${DS.catalog}/${key}`, run: () => cached(DS.catalog, key, catalogCalls[key]) })),
      report,
      guard,
    );

    // 3. recepcie v rozsahu PLAN_VIEW (karty: RESERVATION_VIEW)
    const receptions = (await vault.get<ReceptionView[]>(DS.catalog, 'receptions'))?.value ?? [];
    const allIds = receptions.flatMap((r) => (r.id === undefined ? [] : [r.id]));
    const planIds = receptionsInScope(me, 'PLAN_VIEW', allIds);
    const cardIds = receptionsInScope(me, 'RESERVATION_VIEW', allIds);
    const targets = opt.receptionId === undefined ? planIds : planIds.filter((id) => id === opt.receptionId);
    const dates = new Map<number, string>();

    await runAll(
      targets.map((id) => ({
        name: `${DS.businessDay}/${id}`,
        run: async () => {
          const day: DayView = (await apiCall('get', '/api/receptions/{id}/business-day', { path: { id } })).data;
          await vault.put(DS.businessDay, String(id), day, { syncedAt: now().toISOString() });
          if (day.businessDate) dates.set(id, day.businessDate);
        },
      })),
      report,
      guard,
    );

    const tasks = targets.flatMap((id) => {
      const date = dates.get(id);
      if (!date) return [];
      const { from, to } = windowOf(date);
      const list = [
        {
          name: `${DS.plan}/${id}`,
          run: () =>
            cached(
              DS.plan,
              String(id),
              (headers) => apiCall('get', '/api/accommodation-plan', { query: { receptionId: id, from, to }, headers }),
              (plan): StoredPlan => ({ from, to, plan }),
            ),
        },
        { name: `${DS.movements}/${id}:${date}`, run: () => fetchMovements(id, date) },
        { name: `${DS.movements}/${id}:${addDays(date, 1)}`, run: () => fetchMovements(id, addDays(date, 1)) },
      ];
      if (cardIds.includes(id)) list.push({ name: `${DS.reservation}/${id}`, run: () => syncCards(id, from, to) });
      return list;
    });

    // 4. zoznam hostí pre obchodný deň hlavnej recepcie
    if (hasPermissionCode(me, 'GUEST_LOCATION_VIEW') && opt.receptionId === undefined) {
      const main = me?.defaultReceptionId;
      const storedDay = main === undefined ? undefined : await vault.get<DayView>(DS.businessDay, String(main));
      const date =
        (main !== undefined ? dates.get(main) : undefined) ?? storedDay?.value.businessDate ?? serverTime?.slice(0, 10);
      if (date) {
        tasks.push({
          name: `${DS.guestList}/${date}`,
          run: () =>
            cached(DS.guestList, date, (headers) =>
              apiCall('get', '/api/reports/guest-locations', { query: { date }, headers }),
            ),
        });
      }
    }
    await runAll(tasks, report, guard);

    if (guard.abort) return finish();

    // 5. upratovanie (len po úplne úspešnej synchronizácii, aby sa nezmazalo nič platné)
    if (report.failed.length === 0) await cleanup(planIds, cardIds);

    // 6. platnosť offline dát a čas synchronizácie
    const at = now().toISOString();
    await vault.setSyncState(at, config.maxAgeDays ?? null);
    setStatus({ lastFullSyncAt: at });

    return finish();
  }

  /** Zmaže záznamy, ktoré už nie sú v pláne ani v príchodoch/odchodoch, a staré dni (DLD kap. 5.3 krok 5). */
  async function cleanup(planIds: number[], cardIds: number[]): Promise<void> {
    const dayOf = new Map<number, string>();
    for (const key of await vault.list(DS.businessDay)) {
      const rid = Number(key);
      const day = (await vault.get<DayView>(DS.businessDay, key))?.value;
      if (planIds.includes(rid) && day?.businessDate) dayOf.set(rid, day.businessDate);
      else await vault.remove(DS.businessDay, [key]);
    }
    for (const ds of [DS.plan, DS.reservationSync]) {
      const allowed = ds === DS.plan ? planIds : cardIds;
      const stale = (await vault.list(ds)).filter((k) => !allowed.includes(Number(k)));
      if (stale.length) await vault.remove(ds, stale);
    }

    const referenced = new Set<number>();
    for (const key of await vault.list(DS.plan)) {
      const stored = (await vault.get<StoredPlan>(DS.plan, key))?.value;
      reservationIdsOf(stored?.plan).forEach((id) => referenced.add(id));
    }
    const staleMovements: string[] = [];
    for (const key of await vault.list(DS.movements)) {
      const [rid, date] = key.split(':');
      const min = dayOf.get(Number(rid));
      if (!min || !date || date < min) {
        staleMovements.push(key);
        continue;
      }
      const m = (await vault.get<Movements>(DS.movements, key))?.value;
      for (const x of [...(m?.arrivals ?? []), ...(m?.departures ?? [])]) {
        if (x.reservationId !== undefined) referenced.add(x.reservationId);
      }
    }
    if (staleMovements.length) await vault.remove(DS.movements, staleMovements);

    const staleCards = (await vault.list(DS.reservation)).filter((k) => !referenced.has(Number(k)));
    if (staleCards.length) await vault.remove(DS.reservation, staleCards);

    const minDay = [...dayOf.values()].sort()[0];
    if (minDay) {
      const staleGuestLists = (await vault.list(DS.guestList)).filter((k) => k < minDay);
      if (staleGuestLists.length) await vault.remove(DS.guestList, staleGuestLists);
    }
  }

  async function syncRooms(receptionId: number, roomIds: number[], from: string, to: string): Promise<void> {
    const stored = await vault.get<StoredPlan>(DS.plan, String(receptionId));
    if (!stored || roomIds.length === 0) return;
    const w = stored.value;
    if (from > w.to || to < w.from) return; // mimo okna plánu
    const me = await currentMe();
    const res = await apiCall('get', '/api/accommodation-plan', {
      query: { receptionId, roomIds, from: w.from, to: w.to },
    });
    const fresh = res.data;
    const touched = new Set(roomIds);
    const rooms = [
      ...(w.plan.rooms ?? []).filter((r) => r.roomId === undefined || !touched.has(r.roomId)),
      ...(fresh.rooms ?? []),
    ].sort((a, b) => (a.roomId ?? 0) - (b.roomId ?? 0));
    await vault.put(DS.plan, String(receptionId), { ...w, plan: { ...w.plan, rooms } } satisfies StoredPlan, {
      etag: undefined, // čiastočný výsledok nesedí s ETagom celého plánu
      syncedAt: now().toISOString(),
    });

    if (receptionsInScope(me, 'RESERVATION_VIEW', [receptionId]).length > 0) {
      const ids = reservationIdsOf(fresh);
      if (ids.length > 0) {
        const cards = (await apiCall('get', '/api/offline/reservations', { query: { ids } })).data ?? [];
        await storeCards(cards);
      }
    }

    const day = (await vault.get<DayView>(DS.businessDay, String(receptionId)))?.value.businessDate;
    if (day) {
      await fetchMovements(receptionId, day);
      await fetchMovements(receptionId, addDays(day, 1));
    }
    opts.onDataChanged?.();
  }

  const engine: SyncEngine = {
    fullSync(reason, opt = {}) {
      void reason;
      if (current) return current;
      setStatus({ running: true });
      current = doFullSync(opt)
        .then((report) => {
          setStatus({ partial: report.failed.length > 0, ...(report.failed.length === 0 ? { lastError: undefined } : {}) });
          if (!report.aborted) opts.onDataChanged?.();
          return report;
        })
        .finally(() => {
          current = null;
          setStatus({ running: false });
        });
      return current;
    },
    syncRooms,
    async periodicCheck() {
      if (connection.getState() !== 'online') return;
      await engine.fullSync('periodic');
    },
    status: () => status,
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    syncIntervalSec: () => config.syncIntervalSec ?? DEFAULT_CONFIG.syncIntervalSec,
  };
  return engine;
}
