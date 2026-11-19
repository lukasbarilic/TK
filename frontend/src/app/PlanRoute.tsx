import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { hasPermission, useMe } from '../features/auth';
import type { NewReservationState } from '../features/reservations';
import { originState } from '../shared/navigation/origin';
import { PlanPage, type NewReservationTarget } from '../features/plan';
import { CheckInDialog } from '../features/stays';

/** Skladá obrazovku plánu s prihláseným používateľom (features sa medzi sebou neimportujú). Odkaz `?roomId&date` skočí na izbu a dátum. */
export function PlanRoute() {
  const [params] = useSearchParams();
  const roomId = Number(params.get('roomId'));
  const date = params.get('date');
  const focus = {
    roomId: Number.isInteger(roomId) && params.get('roomId') ? roomId : undefined,
    date: date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : undefined,
  };
  const me = useMe().data;
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const onNewReservation = hasPermission(me, 'RESERVATION_EDIT')
    ? (target?: NewReservationTarget) => {
        const state: NewReservationState = {
          ...originState(t('plan.title'), `${location.pathname}${location.search}`),
          prefill: target && { ...target, receptionId: target.receptionId },
        };
        void navigate('/reservations/new', { state });
      }
    : undefined;
  return <PlanPage me={me} focus={focus} onNewReservation={onNewReservation} renderCheckIn={(target, onClose) => <CheckInDialog me={me} onClose={onClose} target={target} />} />;
}
