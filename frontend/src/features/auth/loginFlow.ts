import { ApiError, isNetError } from '../../shared/api/errors';
import { platform } from '../../shared/platform';
import { isVaultError } from '../../shared/platform/vaultError';
import { fetchMe, login } from './authApi';
import type { Me } from './permissions';

/** Dataset a kľúč v trezore, kam sa po online prihlásení uloží profil pre offline prihlásenie. */
export const SESSION_DATASET = 'session';
export const SESSION_KEY = 'me';

export type LoginFailure = 'no-vault' | 'wrong-password' | 'throttled' | 'expired' | 'deactivated';

/** Prihlásenie zlyhalo kvôli trezoru alebo deaktivovanému účtu (chyby servera 401/423 ostávajú `ApiError`). */
export class LoginFlowError extends Error {
  reason: LoginFailure;
  until?: string;

  constructor(reason: LoginFailure, until?: string) {
    super(reason);
    this.name = 'LoginFlowError';
    this.reason = reason;
    this.until = until;
  }
}

export interface LoginResult {
  me: Me;
  offline: boolean;
}

const OFFLINE_KINDS = new Set(['network', 'timeout', 'tls']);
const isUnreachable = (e: unknown) => isNetError(e) && OFFLINE_KINDS.has(e.kind);
const isDeactivated = (e: unknown) => e instanceof ApiError && e.code === 'ACCOUNT_DISABLED';

/** Priebeh prihlásenia podľa DLD kap. 11. */
export async function loginFlow(username: string, password: string): Promise<LoginResult> {
  const { vault } = platform;
  let me: Me;
  try {
    me = await login(username, password);
  } catch (e) {
    if (isDeactivated(e)) {
      await vault.wipe(username);
      throw new LoginFlowError('deactivated');
    }
    if (isUnreachable(e)) return offlineLogin(username, password);
    throw e; // 401, 423 a ostatné: trezor sa neskúša
  }

  // Pri povinnej zmene hesla sa trezor vytvorí až s novým heslom (useChangePassword).
  if (me.mustChangePassword) return { me, offline: false };

  let state: 'ok' | 'recreate' = 'recreate';
  try {
    if ((await vault.unlock(username, password)) === 'ok') state = 'ok';
  } catch (e) {
    if (!isVaultError(e)) throw e; // napr. throttled: server heslo prijal, trezor sa založí nanovo
  }
  if (state === 'recreate') await vault.create(username, password);
  await vault.put(SESSION_DATASET, SESSION_KEY, me);
  return { me, offline: false };
}

async function offlineLogin(username: string, password: string): Promise<LoginResult> {
  const { vault } = platform;
  let result;
  try {
    result = await vault.unlock(username, password);
  } catch (e) {
    if (isVaultError(e) && e.kind === 'throttled') throw new LoginFlowError('throttled', e.until);
    throw e;
  }
  if (result === 'missing') throw new LoginFlowError('no-vault');
  if (result === 'expired') throw new LoginFlowError('expired');
  if (result === 'wrong-password') throw new LoginFlowError('wrong-password');
  const entry = await vault.get<Me>(SESSION_DATASET, SESSION_KEY);
  if (!entry) {
    await vault.lock();
    throw new LoginFlowError('no-vault');
  }
  return { me: entry.value, offline: true };
}

/** Po zmene hesla na serveri: ten istý trezor sa prebalí na nové heslo, inak sa založí nový. */
export async function syncVaultAfterPasswordChange(
  username: string,
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  const { vault } = platform;
  const info = await vault.info();
  if (info && info.username.toLowerCase() === username.toLowerCase()) {
    try {
      await vault.rewrap(currentPassword, newPassword);
      return;
    } catch {
      // staré heslo v trezore nesedí – založíme nový trezor nižšie
    }
  }
  await vault.create(username, newPassword);
  try {
    await vault.put(SESSION_DATASET, SESSION_KEY, await fetchMe());
  } catch {
    // profil sa uloží pri ďalšom online prihlásení
  }
}
