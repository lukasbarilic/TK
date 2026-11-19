import type { components } from '../api/schema';
import { hasPermissionCode } from '../offline/scope';
import type { StatusAction } from './statusApi';

type Me = components['schemas']['MeView'];

/** Akcie ponúkané v danom stave karty; prvá je hlavná akcia dňa (Stavy.dc.html). Opravy vyžadujú `RESERVATION_STATUS_FIX`. */
export function statusActions(status: string | undefined, me: Me | null | undefined): StatusAction[] {
  const normal = hasPermissionCode(me, 'RESERVATION_STATUS');
  const fix = hasPermissionCode(me, 'RESERVATION_STATUS_FIX');
  switch (status) {
    case 'RESERVED':
      return normal ? ['checkIn', 'noShow', 'cancel'] : [];
    case 'CHECKED_IN':
      return [...(normal ? (['checkOut'] as const) : []), ...(fix ? (['undoCheckIn'] as const) : [])];
    case 'NO_SHOW':
      return fix ? ['revertNoShow'] : [];
    default:
      return [];
  }
}
