import type { components } from '../../shared/api/schema';

export type Me = components['schemas']['MeView'];
type PermissionView = components['schemas']['PermissionView'];

const covers = (p: PermissionView, receptionId?: number): boolean => {
  const scopes = p.scopes;
  // Bez rozsahu (alebo null scope) = všetky recepcie.
  if (!scopes || scopes.length === 0) return true;
  if (receptionId === undefined) return true;
  // Rozsah len na stredisko (bez recepcie) klient nevie overiť; rozhodne server.
  return scopes.some((s) => s.receptionId == null || s.receptionId === receptionId);
};

/** Má používateľ oprávnenie `code` (a ak je zadaná `receptionId`, tak aj na danú recepciu)? */
export function hasPermission(me: Me | null | undefined, code: string, receptionId?: number): boolean {
  return (me?.permissions ?? []).some((p) => p.code === code && covers(p, receptionId));
}

/** Je recepcia v rozsahu aspoň jedného z oprávnení `codes`? */
export function receptionInScope(me: Me | null | undefined, codes: string[], receptionId: number): boolean {
  return codes.some((c) => hasPermission(me, c, receptionId));
}

export const hasRole = (me: Me | null | undefined, code: string): boolean =>
  (me?.roles ?? []).some((r) => r.code === code);
