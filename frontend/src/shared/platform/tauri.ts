import type { Platform } from './types';

const notImplemented = (): never => {
  throw new Error('Tauri platforma nie je implementovaná');
};

// Kostra; implementácia príde v tikete F02.
export function createTauriPlatform(): Platform {
  return {
    kind: 'tauri',
    http: () => Promise.reject(new Error('Tauri platforma nie je implementovaná')),
    live: { connect: notImplemented },
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
    config: { serverUrl: notImplemented, setServerUrl: notImplemented, deviceId: notImplemented },
    app: { version: notImplemented, checkUpdate: notImplemented, installUpdate: notImplemented },
    files: { save: notImplemented },
  };
}
