import { doctor, receptionist } from '../../shared/test/handlers';
import { hasPermission, receptionInScope } from './permissions';

describe('hasPermission', () => {
  it('bez rozsahu platí pre všetky recepcie', () => {
    expect(hasPermission(doctor, 'GUEST_LOCATION_VIEW')).toBe(true);
    expect(hasPermission(doctor, 'GUEST_LOCATION_VIEW', 99)).toBe(true);
  });
  it('s rozsahom platí len pre danú recepciu', () => {
    expect(hasPermission(receptionist, 'PLAN_VIEW')).toBe(true);
    expect(hasPermission(receptionist, 'PLAN_VIEW', 2)).toBe(true);
    expect(hasPermission(receptionist, 'PLAN_VIEW', 1)).toBe(false);
  });
  it('chýbajúce oprávnenie alebo používateľ = nie', () => {
    expect(hasPermission(doctor, 'PLAN_VIEW')).toBe(false);
    expect(hasPermission(null, 'PLAN_VIEW')).toBe(false);
  });
  it('rozsah recepcií', () => {
    expect(receptionInScope(receptionist, ['PLAN_VIEW'], 1)).toBe(false);
    expect(receptionInScope(receptionist, ['PLAN_VIEW'], 2)).toBe(true);
  });
});
