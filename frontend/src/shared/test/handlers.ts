import { http, HttpResponse } from 'msw';
import type { Me } from '../../features/auth';
import { server } from './server';

/** Fiktívni používatelia pre testy. */
export const receptionist: Me = {
  id: 1, username: 'recepcna.demo', displayName: 'Recepčná Ukážková', defaultReceptionId: 2,
  mustChangePassword: false,
  roles: [{ code: 'RECEPTIONIST', name: 'Recepčná' }],
  permissions: [
    { code: 'PLAN_VIEW', scopes: [{ receptionId: 2 }] },
    { code: 'RESERVATION_VIEW', scopes: [{ receptionId: 2 }] },
    { code: 'BUSINESS_DAY_CLOSE', scopes: [{ receptionId: 2 }] },
    { code: 'GUEST_LOCATION_VIEW' },
    { code: 'STAY_CREATE', limit: 14, scopes: [{ receptionId: 2 }] },
    { code: 'STAY_OTHER_CATEGORY', scopes: [{ receptionId: 2 }] },
  ],
};
export const doctor: Me = {
  id: 2, username: 'lekar.demo', displayName: 'Lekár Ukážkový', mustChangePassword: false,
  roles: [{ code: 'DOCTOR', name: 'Lekár' }],
  permissions: [{ code: 'GUEST_LOCATION_VIEW' }],
};
export const admin: Me = {
  id: 3, username: 'admin.demo', displayName: 'Admin Ukážkový', mustChangePassword: false,
  roles: [{ code: 'ADMIN', name: 'Administrátor' }],
  permissions: [{ code: 'USER_ADMIN' }, { code: 'AUDIT_VIEW' }],
};

export const demoReceptions = [
  { id: 1, code: 'R1', name: 'Recepcia A', active: true },
  { id: 2, code: 'R2', name: 'Recepcia B', active: true },
];

export function mockApi(me: Me | null) {
  server.use(
    http.get('/api/me', () =>
      me ? HttpResponse.json(me) : HttpResponse.json({ status: 401, code: 'UNAUTHORIZED' }, { status: 401 }),
    ),
    http.get('/api/catalog/receptions', () => HttpResponse.json(demoReceptions)),
    http.get('/api/receptions/:id/business-day', ({ params }) =>
      HttpResponse.json({ receptionId: Number(params.id), businessDate: '2026-11-18', status: 'OPEN' }),
    ),
  );
}
