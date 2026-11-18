import { createTauriPlatform } from './tauri';
import type { Platform } from './types';
import { createWebPlatform } from './web';

export function selectPlatform(): Platform {
  return '__TAURI_INTERNALS__' in window ? createTauriPlatform() : createWebPlatform();
}

export const platform: Platform = selectPlatform();
export type { Platform } from './types';
