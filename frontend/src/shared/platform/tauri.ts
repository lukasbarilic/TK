import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import type { HttpRequest, HttpResponse, LiveEvent, LiveState, Platform } from './types';

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

export function createTauriPlatform(): Platform {
  return {
    kind: 'tauri',
    http,
    live: { connect: connectLive },
    vault: {
      exists: notImplemented,
      create: notImplemented,
      unlock: notImplemented,
      rewrap: notImplemented,
      lock: notImplemented,
      wipe: notImplemented,
      put: notImplemented,
      get: notImplemented,
      list: notImplemented,
      remove: notImplemented,
      info: notImplemented,
    },
    config: {
      serverUrl: () => invoke<string | null>('config_get'),
      setServerUrl: (url) => invoke<void>('config_set_server_url', { url }),
      deviceId: () => invoke<string>('config_device_id'),
    },
    app: {
      version: () => invoke<string>('app_version'),
      checkUpdate: notImplemented, // F16
      installUpdate: notImplemented, // F16
    },
    files: { save: notImplemented },
  };
}
