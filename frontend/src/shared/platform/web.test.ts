import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { buildUrl, createWebPlatform } from './web';

const ORIGIN = 'http://localhost:3000';

beforeEach(() => {
  const nativeFetch = globalThis.fetch; // už zachytávaný MSW (server.listen beží v setup)
  // Node fetch neprijíma relatívne URL; správame sa ako prehliadač na pôvode aplikácie.
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    nativeFetch(typeof input === 'string' ? new URL(input, ORIGIN) : input, init),
  );
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
});
afterEach(() => vi.unstubAllGlobals());

describe('buildUrl', () => {
  it('skladá query vrátane polí a preskakuje undefined', () => {
    expect(
      buildUrl({ path: '/api/x', query: { a: 1, b: ['u', 'v'], c: undefined, d: true } }),
    ).toBe('/api/x?a=1&b=u&b=v&d=true');
  });

  it('odmietne cestu mimo /api/ a /actuator/', () => {
    expect(() => buildUrl({ path: '/etc/passwd' })).toThrowError(
      expect.objectContaining({ kind: 'config' }),
    );
  });
});

describe('web platform – http', () => {
  it('pošle credentials include a CSRF hlavičku z cookie pri zápise', async () => {
    document.cookie = 'XSRF-TOKEN=tok%20123';
    let seen: { csrf: string | null; body: unknown } | undefined;
    server.use(
      http.post(`${ORIGIN}/api/things`, async ({ request }) => {
        seen = { csrf: request.headers.get('x-xsrf-token'), body: await request.json() };
        return HttpResponse.json({ ok: true }, { status: 201, headers: { ETag: '"v1"' } });
      }),
    );
    const res = await createWebPlatform().http({ method: 'POST', path: '/api/things', body: { n: 1 } });
    expect(seen).toEqual({ csrf: 'tok 123', body: { n: 1 } });
    expect(res.status).toBe(201);
    expect(res.json).toEqual({ ok: true });
    expect(res.headers.etag).toBe('"v1"');
  });

  it('CSRF hlavičku nepridáva pri GET', async () => {
    document.cookie = 'XSRF-TOKEN=abc';
    let csrf: string | null = 'x';
    server.use(
      http.get(`${ORIGIN}/api/me`, ({ request }) => {
        csrf = request.headers.get('x-xsrf-token');
        return HttpResponse.json({});
      }),
    );
    await createWebPlatform().http({ method: 'GET', path: '/api/me' });
    expect(csrf).toBeNull();
  });

  it('vráti bajty pri accept: bytes a stav 304 bez tela', async () => {
    server.use(
      http.get(`${ORIGIN}/api/file`, () => new HttpResponse(new Uint8Array([1, 2, 3]))),
      http.get(`${ORIGIN}/api/cached`, () => new HttpResponse(null, { status: 304 })),
    );
    const p = createWebPlatform();
    expect((await p.http({ method: 'GET', path: '/api/file', accept: 'bytes' })).bytes).toEqual(
      new Uint8Array([1, 2, 3]),
    );
    const cached = await p.http({ method: 'GET', path: '/api/cached' });
    expect(cached.status).toBe(304);
    expect(cached.json).toBeUndefined();
  });

  it('sieťovú chybu vyhodí ako NetError', async () => {
    server.use(http.get(`${ORIGIN}/api/down`, () => HttpResponse.error()));
    await expect(createWebPlatform().http({ method: 'GET', path: '/api/down' })).rejects.toMatchObject({
      kind: 'network',
    });
  });
});

describe('web platform – trezor v pamäti', () => {
  it('create, put/get/list/remove, lock a unlock', async () => {
    const { vault } = createWebPlatform();
    expect(await vault.exists('Jana')).toBe(false);
    await vault.create('Jana', 'heslo1');
    expect(await vault.exists('jana')).toBe(true);
    await vault.put('plan', '1', { a: 1 }, { syncedAt: '2026-11-18T10:00:00Z', etag: 'e' });
    await vault.put('plan', '2', { a: 2 });
    expect((await vault.list('plan')).sort()).toEqual(['1', '2']);
    expect(await vault.get('plan', '1')).toEqual({
      value: { a: 1 },
      meta: { syncedAt: '2026-11-18T10:00:00Z', etag: 'e' },
    });
    await vault.remove('plan', ['1']);
    expect(await vault.get('plan', '1')).toBeNull();

    await vault.lock();
    await expect(vault.list('plan')).rejects.toThrow('locked');
    expect(await vault.unlock('jana', 'zle')).toBe('wrong-password');
    expect(await vault.unlock('jana', 'heslo1')).toBe('ok');
    expect(await vault.list('plan')).toEqual(['2']);
    expect(await vault.unlock('nikto', 'x')).toBe('missing');
  });

  it('rewrap zmení heslo, dáta ostanú; wipe trezor zmaže', async () => {
    const { vault } = createWebPlatform();
    await vault.create('jana', 'a');
    await vault.put('session', 'me', { x: 1 });
    await expect(vault.rewrap('zle', 'b')).rejects.toThrow();
    await vault.rewrap('a', 'b');
    await vault.lock();
    expect(await vault.unlock('jana', 'a')).toBe('wrong-password');
    expect(await vault.unlock('jana', 'b')).toBe('ok');
    expect(await vault.get('session', 'me')).not.toBeNull();
    await vault.wipe('jana');
    expect(await vault.exists('jana')).toBe(false);
    expect(await vault.info()).toBeNull();
  });
});

describe('web platform – ostatné', () => {
  it('checkUpdate je vždy null a deviceId je stabilné', async () => {
    const p = createWebPlatform();
    expect(await p.app.checkUpdate()).toBeNull();
    expect(await p.config.deviceId()).toBe(await p.config.deviceId());
  });
});
