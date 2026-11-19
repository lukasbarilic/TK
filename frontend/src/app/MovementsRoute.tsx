import { useQuery } from '@tanstack/react-query';
import { hasPermission, useMe } from '../features/auth';
import { MovementsPage } from '../features/movements';
import { loadReservation } from '../features/reservations/reservationsApi';
import { CheckInDialog } from '../features/stays';
import type { components } from '../shared/api/schema';

type Me = components['schemas']['MeView'];

/** Dialóg „Prideliť lôžko“ pre kartu z príchodov: z detailu karty sa vezme kategória a termín. */
function AssignBedDialog({ me, id, onClose }: { me: Me | null | undefined; id: number; onClose: () => void }) {
  const card = useQuery({ queryKey: ['reservations', 'detail', id], queryFn: () => loadReservation(id) });
  const d = card.data && !card.data.empty ? card.data.data : undefined;
  if (!d) return null;
  return (
    <CheckInDialog
      me={me}
      onClose={onClose}
      card={{ id, reservationNumber: d.reservationNumber, arrivalDate: d.arrivalDate, departureDate: d.departureDate, bookedCategoryId: d.bookedCategoryId }}
    />
  );
}

/** Skladá obrazovku príchodov a odchodov s prihláseným používateľom; pridelenie lôžka len s `STAY_CREATE`. */
export function MovementsRoute() {
  const me = useMe().data;
  return (
    <MovementsPage
      me={me}
      renderAssignBed={hasPermission(me, 'STAY_CREATE') ? (c, onClose) => <AssignBedDialog me={me} id={c.id} onClose={onClose} /> : undefined}
    />
  );
}
