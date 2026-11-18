import { useParams } from 'react-router-dom';
import { hasPermission, useMe } from '../features/auth';
import { ReservationDetailPage } from '../features/reservations';

/** Skladá detail karty s ID z URL; kontakty ukáže len s oprávnením `RESERVATION_VIEW`. */
export function ReservationDetailRoute() {
  const { id } = useParams();
  const me = useMe().data;
  const n = Number(id);
  return Number.isInteger(n) ? <ReservationDetailPage id={n} canSeeContacts={hasPermission(me, 'RESERVATION_VIEW')} /> : null;
}
