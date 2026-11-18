import { useMe } from '../features/auth';
import { BusinessDayClosePage } from '../features/business-day';

/** Skladá obrazovku nočnej uzávierky s prihláseným používateľom. */
export function BusinessDayRoute() {
  return <BusinessDayClosePage me={useMe().data} />;
}
