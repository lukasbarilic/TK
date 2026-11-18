import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { queryKeys } from '../../shared/api/queryKeys';
import { connection, flushJournal } from '../../shared/offline';
import { platform } from '../../shared/platform';
import { changePassword, fetchMe, login, logout } from './authApi';
import { loginFlow, syncVaultAfterPasswordChange } from './loginFlow';
import { hasPermission, type Me } from './permissions';
import { clearSessionExpired } from './session';

/** Prihlásený používateľ; `null` = odhlásený. */
export function useMe() {
  return useQuery<Me | null>({ queryKey: queryKeys.me, queryFn: fetchMe, staleTime: Infinity });
}

export function usePermission(code: string, receptionId?: number): boolean {
  return hasPermission(useMe().data, code, receptionId);
}

export function useLogin() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { username: string; password: string }) => loginFlow(v.username, v.password),
    meta: { skipSessionExpired: true },
    onSuccess: async (res) => {
      clearSessionExpired();
      connection.reset(res.offline ? 'offline' : 'online');
      // Pri povinnej zmene hesla server GET /api/me odmietne (403 PASSWORD_CHANGE_REQUIRED),
      // preto sa použije profil z odpovede na prihlásenie.
      if (res.offline || res.me.mustChangePassword) qc.setQueryData<Me | null>(queryKeys.me, res.me);
      else await qc.invalidateQueries({ queryKey: queryKeys.me });
    },
  });
}

/** Opätovné prihlásenie v dialógu „Relácia vypršala“: po úspechu sa všetky dáta obnovia. */
export function useReauthenticate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (v: { username: string; password: string }) => login(v.username, v.password),
    meta: { skipSessionExpired: true },
    onSuccess: async () => {
      clearSessionExpired();
      await qc.invalidateQueries();
    },
  });
}

export function useChangePassword() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (v: { currentPassword: string; newPassword: string }) => {
      const me = qc.getQueryData<Me | null>(queryKeys.me);
      await changePassword(v.currentPassword, v.newPassword);
      if (me?.username) await syncVaultAfterPasswordChange(me.username, v.currentPassword, v.newPassword);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.me }),
  });
}

function clearSession(qc: QueryClient) {
  clearSessionExpired();
  connection.reset('online');
  qc.removeQueries({ predicate: (q) => q.queryKey[0] !== queryKeys.me[0] });
  qc.setQueryData<Me | null>(queryKeys.me, null);
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      try {
        await logout();
      } finally {
        await platform.vault.lock();
      }
    },
    meta: { skipSessionExpired: true },
    onSettled: () => {
      clearSession(qc);
    },
  });
}

/** „Odhlásiť a vymazať údaje z tohto počítača“: odhlási (aj bez servera) a zmaže trezor používateľa. */
export function useLogoutAndWipe() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (username: string) => {
      // neodoslaný denník čítaní sa pred vymazaním pokúsi odoslať, ak je spojenie (DLD kap. 7)
      if (connection.getState() === 'online') await flushJournal();
      try {
        await logout();
      } catch {
        // server nemusí byť dostupný; lokálne údaje sa zmažú tak či tak
      } finally {
        await platform.vault.lock();
        await platform.vault.wipe(username);
      }
    },
    meta: { skipSessionExpired: true },
    onSettled: () => clearSession(qc),
  });
}
