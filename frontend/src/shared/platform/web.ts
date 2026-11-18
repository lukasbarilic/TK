import type {
  EntryMeta,
  HttpRequest,
  HttpResponse,
  LiveEvent,
  LiveState,
  NetError,
  Platform,
  Vault,
} from './types';

const DEFAULT_TIMEOUT_MS = 15_000;
const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const LIVE_EVENT_TYPES: LiveEvent['type'][] = [
  'plan-changed',
  'business-day-changed',
  'notification',
  'heartbeat',
];

function readCookie(name: string): string | undefined {
  for (const part of document.cookie.split(';')) {
    const [k, ...v] = part.trim().split('=');
    if (k === name) return decodeURIComponent(v.join('='));
  }
  return undefined;
}

export function buildUrl(req: Pick<HttpRequest, 'path' | 'query'>): string {
  if (!req.path.startsWith('/api/') && !req.path.startsWith('/actuator/')) {
    throw { kind: 'config', message: `Nepovolená cesta: ${req.path}` } satisfies NetError;
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(req.query ?? {})) {
    if (value === undefined) continue;
    if (Array.isArray(value)) value.forEach((v) => params.append(key, v));
    else params.append(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${req.path}?${qs}` : req.path;
}

async function http(req: HttpRequest): Promise<HttpResponse> {
  const url = buildUrl(req);
  const headers: Record<string, string> = { ...req.headers };
  headers.Accept = req.accept === 'bytes' ? '*/*' : 'application/json';
  if (req.body !== undefined) headers['Content-Type'] = 'application/json';
  if (MUTATING.has(req.method)) {
    const token = readCookie('XSRF-TOKEN');
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), req.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  try {
    const res = await fetch(url, {
      method: req.method,
      headers,
      body: req.body === undefined ? undefined : JSON.stringify(req.body),
      credentials: 'include',
      signal: controller.signal,
    });
    const outHeaders: Record<string, string> = {};
    res.headers.forEach((value, key) => {
      outHeaders[key.toLowerCase()] = value;
    });
    const out: HttpResponse = { status: res.status, headers: outHeaders };
    if (req.accept === 'bytes') {
      out.bytes = new Uint8Array(await res.arrayBuffer());
    } else {
      const text = await res.text();
      if (text) {
        try {
          out.json = JSON.parse(text);
        } catch {
          // telo nie je JSON (napr. chybová stránka proxy) – json ostane nedefinované
        }
      }
    }
    return out;
  } catch (e) {
    const aborted = e instanceof DOMException && e.name === 'AbortError';
    throw {
      kind: aborted ? 'timeout' : 'network',
      message: e instanceof Error ? e.message : String(e),
    } satisfies NetError;
  } finally {
    clearTimeout(timer);
  }
}

function connectLive(
  onEvent: (e: LiveEvent) => void,
  onState: (s: LiveState) => void,
): () => void {
  onState('connecting');
  const source = new EventSource('/api/events', { withCredentials: true });
  source.onopen = () => onState('open');
  source.onerror = () => onState(source.readyState === EventSource.CLOSED ? 'closed' : 'connecting');
  for (const type of LIVE_EVENT_TYPES) {
    source.addEventListener(type, (ev) => {
      const m = ev as MessageEvent<string>;
      let data: unknown = m.data;
      try {
        data = JSON.parse(m.data);
      } catch {
        // ponechaj surový text
      }
      onEvent({ type, data, id: m.lastEventId || undefined });
    });
  }
  return () => {
    source.close();
    onState('closed');
  };
}

/** Trezor v pamäti (len pre vývoj a testy); heslo sa porovnáva pri unlock. */
function createMemoryVault(): Vault {
  interface Entry { value: unknown; meta: EntryMeta }
  interface Store { password: string; data: Map<string, Entry>; created: string }
  const stores = new Map<string, Store>();
  let current: { username: string; store: Store } | null = null;
  const k = (u: string) => u.toLowerCase();
  const id = (dataset: string, key: string) => `${dataset}\u0000${key}`;
  const open = (): Store => {
    if (!current) throw new Error('locked');
    return current.store;
  };

  return {
    exists: async (u) => stores.has(k(u)),
    create: async (u, password) => {
      const store: Store = { password, data: new Map(), created: new Date().toISOString() };
      stores.set(k(u), store);
      current = { username: u, store };
    },
    unlock: async (u, password) => {
      const store = stores.get(k(u));
      if (!store) return 'missing';
      if (store.password !== password) return 'wrong-password';
      current = { username: u, store };
      return 'ok';
    },
    rewrap: async (oldPassword, newPassword) => {
      const store = open();
      if (store.password !== oldPassword) throw new Error('wrong-password');
      store.password = newPassword;
    },
    lock: async () => {
      current = null;
    },
    wipe: async (u) => {
      stores.delete(k(u));
      if (current && k(current.username) === k(u)) current = null;
    },
    put: async (dataset, key, value, meta) => {
      open().data.set(id(dataset, key), {
        value: structuredClone(value),
        meta: meta ?? { syncedAt: new Date().toISOString() },
      });
    },
    get: async <T>(dataset: string, key: string) => {
      const e = open().data.get(id(dataset, key));
      return e ? { value: structuredClone(e.value) as T, meta: e.meta } : null;
    },
    list: async (dataset) =>
      [...open().data.keys()]
        .filter((x) => x.startsWith(`${dataset}\u0000`))
        .map((x) => x.slice(dataset.length + 1)),
    remove: async (dataset, keys) => {
      const store = open();
      keys.forEach((key) => store.data.delete(id(dataset, key)));
    },
    info: async () =>
      current
        ? {
            username: current.username,
            sizeBytes: JSON.stringify([...current.store.data.values()]).length,
          }
        : null,
  };
}

export function createWebPlatform(): Platform {
  let serverUrl: string | null = null;
  let deviceId: string | null = null;
  return {
    kind: 'web',
    http,
    live: { connect: connectLive },
    vault: createMemoryVault(),
    config: {
      serverUrl: async () => serverUrl,
      setServerUrl: async (url) => {
        serverUrl = url;
      },
      deviceId: async () => (deviceId ??= crypto.randomUUID()),
    },
    app: {
      version: async () => '0.1.0',
      checkUpdate: async () => null,
      installUpdate: async () => {},
    },
    files: {
      save: async (defaultName, bytes) => {
        const url = URL.createObjectURL(new Blob([bytes as BlobPart]));
        const a = document.createElement('a');
        a.href = url;
        a.download = defaultName;
        a.click();
        URL.revokeObjectURL(url);
        return true;
      },
    },
  };
}
