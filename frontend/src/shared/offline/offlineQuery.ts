import { isNetError } from '../api/errors';
import { platform } from '../platform';
import { connection, type Connection } from './connection';

export interface FetchResult<T> {
  status: 200 | 304;
  data?: T;
  etag?: string;
}

/** Výsledok čítania (DLD kap. 5.4): `empty` = v trezore nič nie je („Tieto údaje nie sú na tomto počítači uložené“). */
export type OfflineResult<T> =
  | { data: T; source: 'server' | 'cache'; syncedAt: string; empty: false }
  | { data: null; source: 'cache'; syncedAt: undefined; empty: true };

interface Deps {
  vault: Pick<typeof platform.vault, 'get' | 'put'>;
  connection: Pick<Connection, 'getState' | 'onNetworkError'>;
  now: () => Date;
}

const defaultDeps = (): Deps => ({ vault: platform.vault, connection, now: () => new Date() });

async function fromVault<T>(deps: Deps, ds: string, key: string): Promise<OfflineResult<T>> {
  try {
    const entry = await deps.vault.get<T>(ds, key);
    if (entry) return { data: entry.value, source: 'cache', syncedAt: entry.meta.syncedAt, empty: false };
  } catch {
    // zamknutý trezor = žiadne dáta
  }
  return { data: null, source: 'cache', syncedAt: undefined, empty: true };
}

/**
 * Čítanie dát s trezorom (DLD kap. 5.4).
 * - `online`: `fetcher` s uloženým ETagom; `200` → uloží a vráti `server`, `304` → dáta z trezoru (`cache`, `syncedAt` sa obnoví).
 *   Sieťová chyba → stav `checking` a dáta z trezoru.
 * - inak (offline, relácia vypršala, kontrola spojenia): dáta z trezoru.
 * Zápis do denníka čítaní (kap. 7) je mimo rozsahu tohto modulu (F15).
 */
export async function offlineQuery<T>(
  ds: string,
  key: string,
  fetcher: (etag?: string) => Promise<FetchResult<T>>,
  deps: Deps = defaultDeps(),
): Promise<OfflineResult<T>> {
  if (deps.connection.getState() !== 'online') return fromVault<T>(deps, ds, key);

  let stored: Awaited<ReturnType<Deps['vault']['get']>> = null;
  try {
    stored = await deps.vault.get<T>(ds, key);
  } catch {
    // bez trezora sa ETag nepoužije
  }
  let res: FetchResult<T>;
  try {
    res = await fetcher(stored?.meta.etag);
  } catch (e) {
    if (isNetError(e)) {
      deps.connection.onNetworkError();
      return fromVault<T>(deps, ds, key);
    }
    throw e;
  }
  const syncedAt = deps.now().toISOString();
  if (res.status === 304) {
    if (!stored) return fromVault<T>(deps, ds, key);
    await deps.vault.put(ds, key, stored.value, { etag: stored.meta.etag, syncedAt });
    return { data: stored.value as T, source: 'cache', syncedAt, empty: false };
  }
  if (res.data === undefined) return fromVault<T>(deps, ds, key);
  try {
    await deps.vault.put(ds, key, res.data, { etag: res.etag, syncedAt });
  } catch {
    // zápis do trezora nesmie zhodiť zobrazenie
  }
  return { data: res.data, source: 'server', syncedAt, empty: false };
}
