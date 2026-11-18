import type { HttpRequest, HttpResponse } from '../platform/types';
import { apiCall } from './client';
import { ApiError } from './errors';

function fake(res: HttpResponse) {
  const calls: HttpRequest[] = [];
  return {
    calls,
    http: async (req: HttpRequest) => {
      calls.push(req);
      return res;
    },
  };
}

describe('apiCall', () => {
  it('zavolá Platform.http a vráti dáta', async () => {
    const f = fake({ status: 200, headers: {}, json: { username: 'demo' } });
    const r = await apiCall('get', '/api/me', undefined, f);
    expect(f.calls[0]).toMatchObject({ method: 'GET', path: '/api/me' });
    expect(r.status).toBe(200);
    expect(r.data).toEqual({ username: 'demo' });
  });

  it('doplní parametre cesty a zakóduje ich', async () => {
    const f = fake({ status: 200, headers: {}, json: {} });
    await apiCall('get', '/api/reservations/{id}', { path: { id: 7 } }, f);
    expect(f.calls[0].path).toBe('/api/reservations/7');
  });

  it('chybu 409 prevedie na ApiError s ProblemDetail', async () => {
    const f = fake({
      status: 409,
      headers: { 'x-audit-event-id': '9' },
      json: { code: 'BED_OCCUPIED', confirmable: true, warnings: ['W'] },
    });
    const err = await apiCall('get', '/api/me', undefined, f).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 409, code: 'BED_OCCUPIED', confirmable: true, auditEventId: '9' });
  });

  it('304 nie je chyba', async () => {
    const f = fake({ status: 304, headers: {} });
    const r = await apiCall('get', '/api/me', undefined, f);
    expect(r.status).toBe(304);
  });
});
