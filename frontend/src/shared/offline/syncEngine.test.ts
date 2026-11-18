import { http, HttpResponse, type JsonBodyType } from 'msw';
import { doctor, receptionist } from '../test/handlers';
import { server } from '../test/server';
import type { components } from '../api/schema';
import { createWebPlatform } from '../platform/web';
import { DS, SESSION_KEYS, movementsKey } from './datasets';
import { createSyncEngine } from './syncEngine';

type Me = components['schemas']['MeView'];

const NOW = new Date('2026-11-18T08:00:00Z');
const config = {
  idleTimeoutSec: 1800,
  serverTime: '2026-11-18T09:00:00+01:00',
  offline: { planDaysBack: 1, planDaysAhead: 14, syncIntervalSec: 300, maxAgeDays: 7 },
};
const stay = (reservationId: number) => ({ type: 'STAY', reservationId, stayId: reservationId * 10 });
const plan = {
  receptions: [],
  rooms: [
    { roomId: 1, bars: [stay(100)] },
    { roomId: 7, bars: [stay(101)] },
    { roomId: 9, bars: [stay(102)] },
  ],
};

interface Setup {
  requests: URL[];
  ifNoneMatch: Map<string, string | null>;
  etags: { value: string };
}

/** Falošný server; `etags.value` sa posiela ako ETag a pri zhode If-None-Match vráti 304. */
function mockServer(me: Me): Setup {
  const s: Setup = { requests: [], ifNoneMatch: new Map(), etags: { value: '"v1"' } };
  const etagged = (request: Request, body: JsonBodyType) => {
    const url = new URL(request.url);
    s.requests.push(url);
    const inm = request.headers.get('if-none-match');
    s.ifNoneMatch.set(url.pathname + url.search, inm);
    if (inm === s.etags.value) return new HttpResponse(null, { status: 304 });
    return HttpResponse.json(body, { headers: { ETag: s.etags.value } });
  };
  const plain = (request: Request, body: JsonBodyType) => {
    s.requests.push(new URL(request.url));
    return HttpResponse.json(body);
  };
  server.use(
    http.get('/api/client-config', ({ request }) => plain(request, config)),
    http.get('/api/me', ({ request }) => plain(request, me)),
    ...['centres', 'buildings', 'rooms', 'room-categories', 'payers'].map((c) =>
      http.get(`/api/catalog/${c}`, ({ request }) => etagged(request, [{ id: 1 }])),
    ),
    http.get('/api/catalog/receptions', ({ request }) =>
      etagged(request, [{ id: 1, name: 'Recepcia A' }, { id: 2, name: 'Recepcia B' }]),
    ),
    http.get('/api/receptions/:id/business-day', ({ request, params }) =>
      plain(request, { receptionId: Number(params.id), businessDate: '2026-11-18', status: 'OPEN' }),
    ),
    http.get('/api/accommodation-plan', ({ request }) => {
      const roomIds = new URL(request.url).searchParams.getAll('roomIds').map(Number);
      const rooms = roomIds.length ? plan.rooms.filter((r) => roomIds.includes(r.roomId)) : plan.rooms;
      return etagged(request, { from: '2026-11-17', to: '2026-12-02', rooms });
    }),
    http.get('/api/receptions/:id/movements', ({ request, params }) =>
      etagged(request, {
        receptionId: Number(params.id),
        date: new URL(request.url).searchParams.get('date'),
        arrivals: [{ reservationId: 200 }],
        departures: [],
      }),
    ),
    http.get('/api/offline/reservations', ({ request }) => {
      const url = new URL(request.url);
      const ids = url.searchParams.getAll('ids').map(Number);
      return etagged(request, (ids.length ? ids : [100, 101, 102]).map((id) => ({ id, status: 'RESERVED' })));
    }),
    http.get('/api/reports/guest-locations', ({ request }) => etagged(request, { rows: [] })),
  );
  return s;
}

async function setup(me: Me) {
  const vault = createWebPlatform().vault;
  await vault.create(me.username ?? 'x', 'h');
  const onUnauthorized = vi.fn();
  const onDataChanged = vi.fn();
  const engine = createSyncEngine({
    vault,
    connection: { getState: () => 'online', onUnauthorized },
    onDataChanged,
    now: () => NOW,
  });
  return { vault, engine, onUnauthorized, onDataChanged, srv: mockServer(me) };
}

const paths = (s: Setup) => s.requests.map((u) => u.pathname);

describe('SyncEngine – úplná synchronizácia', () => {
  it('uloží sady podľa DLD 5.1 a okno plánu podľa client-config', async () => {
    const { vault, engine, srv, onDataChanged } = await setup(receptionist);
    const report = await engine.fullSync('login');
    expect(report.failed).toEqual([]);
    expect((await vault.get(DS.session, SESSION_KEYS.me))?.value).toMatchObject({ username: 'recepcna.demo' });
    expect(await vault.get(DS.catalog, 'rooms')).not.toBeNull();
    expect((await vault.get(DS.businessDay, '2').then((e) => e?.value)) as object).toMatchObject({ businessDate: '2026-11-18' });
    expect((await vault.get<{ from: string; to: string }>(DS.plan, '2'))?.value).toMatchObject({
      from: '2026-11-17',
      to: '2026-12-02',
    });
    expect(await vault.list(DS.movements)).toEqual([movementsKey(2, '2026-11-18'), movementsKey(2, '2026-11-19')].sort());
    expect((await vault.list(DS.reservation)).sort()).toEqual(['100', '101', '102']);
    expect(await vault.list(DS.guestList)).toEqual(['2026-11-18']);
    const planReq = srv.requests.find((u) => u.pathname === '/api/accommodation-plan');
    expect(planReq?.searchParams.get('from')).toBe('2026-11-17');
    expect(planReq?.searchParams.get('to')).toBe('2026-12-02');
    expect(onDataChanged).toHaveBeenCalledTimes(1);
  });

  it('ukladá ETag do metadát a platnosť maxAgeDays do trezora', async () => {
    const { vault, engine } = await setup(receptionist);
    await engine.fullSync('login');
    expect((await vault.get(DS.plan, '2'))?.meta).toEqual({ etag: '"v1"', syncedAt: NOW.toISOString() });
    expect((await vault.info())?.lastFullSyncAt).toBe(NOW.toISOString());
    expect(engine.status().lastFullSyncAt).toBe(NOW.toISOString());
    // maxAgeDays (7) sa uplatní pri odomykaní: po 8 dňoch trezor vyprší, skôr ostáva platný
    await vault.lock();
    vi.useFakeTimers({ now: new Date('2026-11-25T07:00:00Z'), toFake: ['Date'] });
    try {
      expect(await vault.unlock('recepcna.demo', 'h')).toBe('ok');
      await vault.lock();
      vi.setSystemTime(new Date('2026-11-27T08:00:00Z'));
      expect(await vault.unlock('recepcna.demo', 'h')).toBe('expired');
    } finally {
      vi.useRealTimers();
    }
  });

  it('304 nezmení dáta, aktualizuje len syncedAt', async () => {
    const { vault, engine, srv } = await setup(receptionist);
    await engine.fullSync('login');
    const before = await vault.get(DS.plan, '2');
    const later = new Date('2026-11-18T08:05:00Z');
    const engine2 = createSyncEngine({
      vault,
      connection: { getState: () => 'online', onUnauthorized: () => {} },
      now: () => later,
    });
    srv.requests.length = 0;
    await engine2.fullSync('periodic');
    expect(srv.ifNoneMatch.get('/api/catalog/rooms')).toBe('"v1"');
    const after = await vault.get(DS.plan, '2');
    expect(after?.value).toEqual(before?.value);
    expect(after?.meta).toEqual({ etag: '"v1"', syncedAt: later.toISOString() });
  });

  it('sady bez oprávnenia sa nesťahujú (lekár: len zoznam hostí)', async () => {
    const { vault, engine, srv } = await setup(doctor);
    await engine.fullSync('login');
    const p = paths(srv);
    expect(p).not.toContain('/api/accommodation-plan');
    expect(p).not.toContain('/api/offline/reservations');
    expect(p.some((x) => x.includes('/movements'))).toBe(false);
    expect(p.some((x) => x.includes('/business-day'))).toBe(false);
    expect(p).toContain('/api/reports/guest-locations');
    expect(await vault.list(DS.plan)).toEqual([]);
    // dátum zo serverTime, lebo lekár nemá obchodný deň
    expect(await vault.list(DS.guestList)).toEqual(['2026-11-18']);
  });

  it('recepcia mimo rozsahu PLAN_VIEW sa nesťahuje; karty len s RESERVATION_VIEW', async () => {
    const me: Me = {
      ...receptionist,
      permissions: [{ code: 'PLAN_VIEW', scopes: [{ receptionId: 1 }] }],
    };
    const { vault, engine, srv } = await setup(me);
    await engine.fullSync('login');
    expect(srv.requests.filter((u) => u.pathname.includes('/business-day')).map((u) => u.pathname)).toEqual([
      '/api/receptions/1/business-day',
    ]);
    expect(paths(srv)).not.toContain('/api/offline/reservations');
    expect(await vault.list(DS.plan)).toEqual(['1']);
  });

  it('401 → session-expired a koniec bez zápisu času synchronizácie', async () => {
    const { vault, engine, onUnauthorized } = await setup(receptionist);
    server.use(http.get('/api/client-config', () => HttpResponse.json({ status: 401 }, { status: 401 })));
    const report = await engine.fullSync('manual');
    expect(report.aborted).toBe('session-expired');
    expect(onUnauthorized).toHaveBeenCalled();
    expect((await vault.info())?.lastFullSyncAt).toBeUndefined();
  });

  it('chyba jednej sady nezastaví ostatné a je v SyncReport', async () => {
    const { vault, engine } = await setup(receptionist);
    server.use(http.get('/api/catalog/payers', () => HttpResponse.json({ status: 500 }, { status: 500 })));
    const report = await engine.fullSync('manual');
    expect(report.failed).toEqual(['catalog/payers']);
    expect(await vault.get(DS.plan, '2')).not.toBeNull();
    expect(engine.status().partial).toBe(true);
  });

  it('upratovanie: zmaže karty mimo plánu a príchodov, staré dni a zrušený rozsah', async () => {
    const { vault, engine } = await setup(receptionist);
    await engine.fullSync('login');
    await vault.put(DS.reservation, '999', { id: 999 }); // nikde nepoužitá karta
    await vault.put(DS.movements, movementsKey(2, '2026-11-10'), {}); // starý deň
    await vault.put(DS.guestList, '2026-11-10', {}); // starý zoznam hostí
    await vault.put(DS.plan, '1', { from: 'x', to: 'y', plan: {} }); // recepcia mimo rozsahu
    await engine.fullSync('manual');
    expect(await vault.get(DS.reservation, '999')).toBeNull();
    expect(await vault.get(DS.reservation, '100')).not.toBeNull();
    expect(await vault.get(DS.movements, movementsKey(2, '2026-11-10'))).toBeNull();
    expect(await vault.get(DS.guestList, '2026-11-10')).toBeNull();
    expect(await vault.get(DS.guestList, '2026-11-18')).not.toBeNull();
    expect(await vault.get(DS.plan, '1')).toBeNull();
  });

  it('business-day-changed: úplná synchronizácia len danej recepcie', async () => {
    const { engine, srv } = await setup({
      ...receptionist,
      permissions: [{ code: 'PLAN_VIEW' }],
    });
    await engine.fullSync('business-day', { receptionId: 1 });
    const days = srv.requests.filter((u) => u.pathname.includes('/business-day')).map((u) => u.pathname);
    expect(days).toEqual(['/api/receptions/1/business-day']);
  });
});

describe('SyncEngine – čiastočná synchronizácia (plan-changed)', () => {
  it('načíta len dotknuté izby a ich karty, ostatné nechá', async () => {
    const { vault, engine, srv } = await setup(receptionist);
    await engine.fullSync('login');
    srv.requests.length = 0;
    srv.etags.value = '"v2"';

    await engine.syncRooms(2, [1, 7], '2026-11-20', '2026-11-22');

    const planReq = srv.requests.find((u) => u.pathname === '/api/accommodation-plan');
    expect(planReq?.searchParams.getAll('roomIds')).toEqual(['1', '7']);
    expect(planReq?.searchParams.get('receptionId')).toBe('2');
    const cards = srv.requests.find((u) => u.pathname === '/api/offline/reservations');
    expect(cards?.searchParams.getAll('ids').sort()).toEqual(['100', '101']); // nie 102 (izba 9)
    expect(cards?.searchParams.has('from')).toBe(false);
    expect(paths(srv)).not.toContain('/api/catalog/rooms');
    expect(paths(srv)).not.toContain('/api/client-config');
    const stored = (await vault.get<{ plan: { rooms: { roomId: number }[] } }>(DS.plan, '2'))?.value;
    expect(stored?.plan.rooms.map((r) => r.roomId)).toEqual([1, 7, 9]);
  });

  it('udalosť mimo okna plánu sa ignoruje', async () => {
    const { engine, srv } = await setup(receptionist);
    await engine.fullSync('login');
    srv.requests.length = 0;
    await engine.syncRooms(2, [1], '2027-03-01', '2027-03-02');
    expect(srv.requests).toEqual([]);
  });
});
