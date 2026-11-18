import type { components } from '../api/schema';

type Me = components['schemas']['MeView'];

/** Recepcie v rozsahu oprávnenia `code` (DLD kap. 5.1); `null` = všetky z `allReceptionIds`. */
export function receptionsInScope(me: Me | null | undefined, code: string, allReceptionIds: number[]): number[] {
  const perms = (me?.permissions ?? []).filter((p) => p.code === code);
  if (perms.length === 0) return [];
  return allReceptionIds.filter((id) =>
    perms.some((p) => !p.scopes || p.scopes.length === 0 || p.scopes.some((s) => s.receptionId == null || s.receptionId === id)),
  );
}

/** Má používateľ oprávnenie `code` (bez ohľadu na rozsah)? */
export const hasPermissionCode = (me: Me | null | undefined, code: string): boolean =>
  (me?.permissions ?? []).some((p) => p.code === code);
