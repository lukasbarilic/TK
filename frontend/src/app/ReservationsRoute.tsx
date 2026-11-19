import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { hasPermission, useMe } from '../features/auth';
import { ReservationsPage } from '../features/reservations';
import { originState } from '../shared/navigation/origin';

/** Zoznam kariet s tlačidlom „+ Nová rezervácia“ (len s `RESERVATION_EDIT`). */
export function ReservationsRoute() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const me = useMe().data;
  return (
    <ReservationsPage
      onNew={hasPermission(me, 'RESERVATION_EDIT') ? () => void navigate('/reservations/new', { state: originState(t('menu.reservations'), '/reservations') }) : undefined}
    />
  );
}
