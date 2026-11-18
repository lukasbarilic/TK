import { useMe } from '../features/auth';
import { GuestListPage } from '../features/guest-list';

/** Skladá obrazovku zoznamu hostí s prihláseným používateľom. */
export function GuestListRoute() {
  return <GuestListPage me={useMe().data} />;
}
