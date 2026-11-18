import { useMe } from '../features/auth';
import { MovementsPage } from '../features/movements';

/** Skladá obrazovku príchodov a odchodov s prihláseným používateľom. */
export function MovementsRoute() {
  return <MovementsPage me={useMe().data} />;
}
