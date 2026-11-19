import { useMe } from '../features/auth';
import { NewReservationPage } from '../features/reservations';

export function NewReservationRoute() {
  return <NewReservationPage me={useMe().data} />;
}
