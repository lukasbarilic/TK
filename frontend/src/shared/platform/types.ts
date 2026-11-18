export interface HttpRequest {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  path: string; // vždy začína /api/ alebo /actuator/
  query?: Record<string, string | number | boolean | string[] | undefined>;
  body?: unknown; // JSON
  headers?: Record<string, string>; // napr. If-None-Match
  accept?: 'json' | 'bytes'; // bytes pre CSV/PDF
  timeoutMs?: number; // predvolene 15 000
}
export interface HttpResponse {
  status: number;
  headers: Record<string, string>; // malé písmená
  json?: unknown;
  bytes?: Uint8Array;
}
export type NetError = { kind: 'network' | 'timeout' | 'tls' | 'config'; message: string };

export type LiveState = 'connecting' | 'open' | 'closed';
export interface LiveEvent {
  type: 'plan-changed' | 'business-day-changed' | 'notification' | 'heartbeat';
  data: unknown;
  id?: string;
}

export interface EntryMeta {
  etag?: string;
  syncedAt: string; // ISO
}

export interface Vault {
  exists(username: string): Promise<boolean>;
  create(username: string, password: string): Promise<void>; // zmaže starý, ak existuje
  unlock(username: string, password: string): Promise<'ok' | 'wrong-password' | 'expired' | 'missing'>;
  rewrap(oldPassword: string, newPassword: string): Promise<void>;
  lock(): Promise<void>;
  wipe(username: string): Promise<void>;
  put(dataset: string, key: string, value: unknown, meta?: EntryMeta): Promise<void>;
  get<T>(dataset: string, key: string): Promise<{ value: T; meta: EntryMeta } | null>;
  list(dataset: string): Promise<string[]>;
  remove(dataset: string, keys: string[]): Promise<void>;
  info(): Promise<{ username: string; lastFullSyncAt?: string; sizeBytes: number } | null>;
  /** Zapíše čas poslednej úplnej synchronizácie a platnosť offline dát (`offline.maxAgeDays`, DLD kap. 4.2). */
  setSyncState(lastFullSyncAt: string, maxAgeDays: number | null): Promise<void>;
}

export interface Platform {
  kind: 'tauri' | 'web';
  http(req: HttpRequest): Promise<HttpResponse>; // vyhodí NetError pri sieťovej chybe
  live: {
    connect(onEvent: (e: LiveEvent) => void, onState: (s: LiveState) => void): () => void;
  };
  vault: Vault;
  config: {
    serverUrl(): Promise<string | null>;
    setServerUrl(url: string): Promise<void>;
    deviceId(): Promise<string>;
  };
  app: {
    version(): Promise<string>;
    checkUpdate(): Promise<{ version: string; notes?: string } | null>;
    installUpdate(onProgress: (pct: number) => void): Promise<void>; // po inštalácii reštart
  };
  files: {
    save(
      defaultName: string,
      bytes: Uint8Array,
      filters: { name: string; extensions: string[] }[],
    ): Promise<boolean>;
  };
}
