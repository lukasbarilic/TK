import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { queryKeys } from '../../shared/api/queryKeys';
import { changePassword, fetchMe, login, logout } from './authApi';
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
    mutationFn: (v: { username: string; password: string }) => login(v.username, v.password),
    meta: { skipSessionExpired: true },
    onSuccess: async () => {
      clearSessionExpired();
      await qc.invalidateQueries({ queryKey: queryKeys.me });
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
    mutationFn: (v: { currentPassword: string; newPassword: string }) =>
      changePassword(v.currentPassword, v.newPassword),
    onSuccess: () => qc.invalidateQueries({ queryKey: queryKeys.me }),
  });
}

export function useLogout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: logout,
    meta: { skipSessionExpired: true },
    onSettled: () => {
      clearSessionExpired();
      qc.removeQueries({ predicate: (q) => q.queryKey[0] !== queryKeys.me[0] });
      qc.setQueryData<Me | null>(queryKeys.me, null);
    },
  });
}
