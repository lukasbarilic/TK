import { hasPermission, hasRole, type Me } from '../features/auth';

export interface MenuItem {
  path: string;
  labelKey: string;
  permission: string;
}

// HLD-frontend kap. 6: menu ukazuje len položky, na ktoré má používateľ oprávnenie.
export const MENU: MenuItem[] = [
  { path: '/plan', labelKey: 'menu.plan', permission: 'PLAN_VIEW' },
  { path: '/reservations', labelKey: 'menu.reservations', permission: 'RESERVATION_VIEW' },
  { path: '/movements', labelKey: 'menu.movements', permission: 'PLAN_VIEW' },
  { path: '/business-day', labelKey: 'menu.businessDayClose', permission: 'BUSINESS_DAY_CLOSE' },
  { path: '/guests', labelKey: 'menu.guests', permission: 'GUEST_LOCATION_VIEW' },
  { path: '/admin/users', labelKey: 'menu.users', permission: 'USER_ADMIN' },
  { path: '/audit', labelKey: 'menu.audit', permission: 'AUDIT_VIEW' },
];

export const visibleMenu = (me: Me | null | undefined): MenuItem[] =>
  MENU.filter((m) => hasPermission(me, m.permission));

/** Úvodná obrazovka podľa roly (HLD-frontend kap. 6); inak prvá povolená položka menu. */
export function homePath(me: Me | null | undefined): string | null {
  const allowed = visibleMenu(me);
  const byRole: [string[], string][] = [
    [['RECEPTIONIST', 'RECEPTION_MANAGER'], '/plan'],
    [['DOCTOR'], '/guests'],
    [['ADMIN'], '/admin/users'],
    [['AUDITOR'], '/audit'],
  ];
  for (const [roles, path] of byRole) {
    if (roles.some((r) => hasRole(me, r)) && allowed.some((m) => m.path === path)) return path;
  }
  return allowed[0]?.path ?? null;
}
