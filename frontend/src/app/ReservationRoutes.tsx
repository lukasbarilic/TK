import { useParams } from 'react-router-dom';
import { hasPermission, useMe } from '../features/auth';
import { ReservationDetailPage } from '../features/reservations';
import { CheckInDialog } from '../features/stays';

/** Skladá detail karty s ID z URL; kontakty ukáže len s oprávnením `RESERVATION_VIEW`, ubytovanie len s `STAY_CREATE`. */
export function ReservationDetailRoute() {
  const { id } = useParams();
  const me = useMe().data;
  const n = Number(id);
  const canCheckIn = hasPermission(me, 'STAY_CREATE');
  return Number.isInteger(n) ? (
    <ReservationDetailPage
      id={n}
      canSeeContacts={hasPermission(me, 'RESERVATION_VIEW')}
      renderCheckIn={
        canCheckIn
          ? (d, onClose) => (
              <CheckInDialog
                me={me}
                onClose={onClose}
                card={{ id: n, reservationNumber: d.reservationNumber, arrivalDate: d.arrivalDate, departureDate: d.departureDate, bookedCategoryId: d.bookedCategoryId }}
              />
            )
          : undefined
      }
    />
  ) : null;
}
