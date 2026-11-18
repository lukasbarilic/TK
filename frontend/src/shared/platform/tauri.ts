import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import type { EntryMeta, HttpRequest, HttpResponse, LiveEvent, LiveState, Platform, Vault } from './types';
import { toVaultError } from './vaultError';

const notImplemented = (): never => {
  throw new Error('Tauri platforma nie je implementovaná');
};

interface WireResponse {
  status: number;
  headers: Record<string, string>;
  json?: unknown;
  bytes?: number[];
}

interface LiveStatePayload {
  state: LiveState;
  reason?: string;
}

async function http(req: HttpRequest): Promise<HttpResponse> {
  const res = await invoke<WireResponse>('http_request', { request: req });
  const { bytes, ...rest } = res;
  return bytes ? { ...rest, bytes: new Uint8Array(bytes) } : rest;
}

function connectLive(
  onEvent: (e: LiveEvent) => void,
  onState: (s: LiveState) => void,
): () => void {
  let closed = false;
  const unlisten: UnlistenFn[] = [];
  const setup = Promise.all([
    listen<LiveEvent>('live://event', (e) => onEvent(e.payload)),
    listen<LiveStatePayload>('live://state', (e) => onState(e.payload.state)),
  ]).then(async (fns) => {
    unlisten.push(...fns);
    if (closed) fns.forEach((f) => f());
    else await invoke('live_connect');
  });
  setup.catch(() => onState('closed'));
  return () => {
    if (closed) return;
    closed = true;
    unlisten.forEach((f) => f());
    void setup.then(() => invoke('live_disconnect')).catch(() => {});
    onState('closed');
  };
}

/** Stiahne a nainštaluje aktualizáciu; jadro po inštalácii aplikáciu reštartuje (preto sa `invoke` nemusí vrátiť). */
async function installUpdate(onProgress: (pct: number) => void): Promise<void> {
  const unlisten = await listen<number>('update://progress', (e) => onProgress(e.payload));
  try {
    await invoke('update_install');
  } finally {
    unlisten();
  }
}

/** Príkazy `vault_*`; chyby jadra sa menia na `VaultError`. DEK sa do JS nikdy nedostane. */
async function vaultCall<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  try {
    return await invoke<T>(cmd, args);
  } catch (e) {
    throw toVaultError(e);
  }
}

function createVault(): Vault {
  return {
    exists: (username) => vaultCall('vault_exists', { username }),
    create: (username, password) => vaultCall('vault_create', { username, password }),
    unlock: (username, password) => vaultCall('vault_unlock', { username, password }),
    rewrap: (oldPassword, newPassword) => vaultCall('vault_rewrap', { oldPassword, newPassword }),
    lock: () => vaultCall('vault_lock'),
    wipe: (username) => vaultCall('vault_wipe', { username }),
    put: (dataset, key, value, meta) => vaultCall('vault_put', { dataset, key, value, meta: meta ?? null }),
    get: <T>(dataset: string, key: string) =>
      vaultCall<{ value: T; meta: EntryMeta } | null>('vault_get', { dataset, key }),
    list: (dataset) => vaultCall('vault_list', { dataset }),
    remove: (dataset, keys) => vaultCall('vault_remove', { dataset, keys }),
    setSyncState: (lastFullSyncAt, maxAgeDays) =>
      vaultCall('vault_set_sync_state', { lastFullSyncAt, maxAgeDays }),
    // zamknutý trezor = žiadne informácie (rovnako ako vo webovom trezore)
    info: async () => {
      try {
        return await vaultCall('vault_info');
      } catch (e) {
        if (toVaultError(e).kind === 'locked') return null;
        throw e;
      }
    },
  };
}

export function createTauriPlatform(): Platform {
  return {
    kind: 'tauri',
    http,
    live: { connect: connectLive },
    vault: createVault(),
    config: {
      serverUrl: () => invoke<string | null>('config_get'),
      setServerUrl: (url) => invoke<void>('config_set_server_url', { url }),
      deviceId: () => invoke<string>('config_device_id'),
    },
    app: {
      version: () => invoke<string>('app_version'),
      checkUpdate: () => invoke<{ version: string; notes?: string } | null>('update_check'),
      installUpdate: installUpdate,
    },
    files: { save: notImplemented },
  };
}
