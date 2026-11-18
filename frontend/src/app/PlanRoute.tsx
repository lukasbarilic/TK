import { useSearchParams } from 'react-router-dom';
import { useMe } from '../features/auth';
import { PlanPage } from '../features/plan';

/** Skladá obrazovku plánu s prihláseným používateľom (features sa medzi sebou neimportujú). Odkaz `?roomId&date` skočí na izbu a dátum. */
export function PlanRoute() {
  const [params] = useSearchParams();
  const roomId = Number(params.get('roomId'));
  const date = params.get('date');
  const focus = {
    roomId: Number.isInteger(roomId) && params.get('roomId') ? roomId : undefined,
    date: date && /^\d{4}-\d{2}-\d{2}$/.test(date) ? date : undefined,
  };
  return <PlanPage me={useMe().data} focus={focus} />;
}
