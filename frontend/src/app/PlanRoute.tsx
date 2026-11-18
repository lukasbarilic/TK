import { useMe } from '../features/auth';
import { PlanPage } from '../features/plan';

/** Skladá obrazovku plánu s prihláseným používateľom (features sa medzi sebou neimportujú). */
export function PlanRoute() {
  return <PlanPage me={useMe().data} />;
}
