import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { connection } from '../../shared/offline';
import { resetHandledEvents } from '../../shared/offline/handledEvents';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { PlanPage } from './PlanPage';
import { BUSINESS_DATE, samplePlan, stayBar } from './testData';
import type { Plan } from './types';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router-dom';

/** Plán používa odkazy (Link), preto potrebuje router. */
const renderInRouter = (ui: ReactElement) => renderWithProviders(<MemoryRouter>{ui}</MemoryRouter>);

const mover: typeof receptionist = { ...receptionist, permissions: [...(receptionist.permissions ?? []), { code: 'STAY_MOVE', scopes: [{ receptionId: 2 }] }] };

/** Rozšírený plán: pobyt 812 (prebiehajúci, lôžko 11) + budúci pobyt 813 (lôžko 21) a 814 (lôžko 22). */
function plan(): Plan {
  const p = samplePlan();
  p.rooms?.[1].bars?.push(
    stayBar(813, 21, '2026-11-20', '2026-11-23', 'Ukážková B.', 'RESERVED'),
    stayBar(814, 22, '2026-11-25', '2026-11-27', 'Vzorová C.', 'RESERVED'),
  );
  return p;
}

let planRequests: URL[] = [];
let calls: { method: string; url: string; body: unknown }[] = [];

function mockServer(handlers: Parameters<typeof server.use> = []) {
  planRequests = [];
  calls = [];
  const record = (method: string) => async ({ request }: { request: Request }) => {
    const text = await request.text();
    calls.push({ method, url: new URL(request.url).pathname + new URL(request.url).search, body: text ? JSON.parse(text) : undefined });
  };
  server.use(
    ...handlers,
    http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, code: 'R2', name: 'Recepcia B', active: true }])),
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: BUSINESS_DATE })),
    http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
    http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
    http.put('/api/me/preferences/:key', async ({ request, params }) => HttpResponse.json({ key: params.key, value: ((await request.json()) as { value: unknown }).value, version: 1 })),
    http.get('/api/accommodation-plan', ({ request }) => {
      const url = new URL(request.url);
      planRequests.push(url);
      const ids = url.searchParams.getAll('roomIds').map(Number);
      const p = plan();
      return HttpResponse.json(ids.length ? { ...p, rooms: p.rooms?.filter((r) => ids.includes(r.roomId as number)) } : p);
    }),
    http.get('/api/reservations/:id/stays', ({ params }) => HttpResponse.json([{ id: Number(params.id) - 100, version: 7 }])),
    http.post('/api/stays/swap', async (ctx) => { await record('POST')(ctx); return HttpResponse.json({ rooms: [{ roomId: 2 }] }, { headers: { 'X-Audit-Event-Id': '501' } }); }),
    http.delete('/api/stays/:id', async (ctx) => { await record('DELETE')(ctx); return new HttpResponse(null, { status: 204 }); }),
  );
}

const bar = (id: string) => screen.getAllByTestId('plan-bar').find((b) => b.getAttribute('data-bar-id') === id) as HTMLElement;
const problem = (status: number, code: string, extra: object = {}) => HttpResponse.json({ status, code, title: code, ...extra }, { status });
const waitCalls = (n: number) => waitFor(() => expect(calls).toHaveLength(n));

function setRect(el: Element, x: number, y: number, w: number, h: number) {
  (el as HTMLElement).getBoundingClientRect = () => ({ x, y, left: x, top: y, right: x + w, bottom: y + h, width: w, height: h, toJSON: () => ({}) });
}
/** Ťahanie cez PointerSensor dnd-kit: jsdom nemá rozmery, preto sa cieľu nastaví obdĺžnik a kurzor sa pustí do jeho stredu. */
function dragTo(source: Element, target: Element, opts: { alt?: boolean; dx?: number } = {}) {
  setRect(target, 100, 100, 540, 30);
  const x = 100 + (opts.dx ?? 270);
  fireEvent.pointerDown(source, { pointerId: 1, button: 0, isPrimary: true, clientX: 10, clientY: 10, altKey: opts.alt });
  for (const cx of [20, x]) fireEvent.pointerMove(document, { pointerId: 1, isPrimary: true, clientX: cx, clientY: 115 });
  fireEvent.pointerUp(document, { pointerId: 1, isPrimary: true, clientX: x, clientY: 115 });
  setRect(target, 0, 0, 0, 0); // aby sa obdĺžniky z predchádzajúcich ťahaní neprekrývali
}
const bedCells = (bedId: number) => document.querySelector(`[data-bed-id="${bedId}"] [data-testid="plan-bed-cells"]`) as HTMLElement;

beforeEach(async () => {
  resetHandledEvents();
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('PlanPage – zmeny ubytovania', () => {
  it('ťahanie pásika na iné lôžko presunie celý pobyt a obnoví dotknuté izby', async () => {
    mockServer([http.post('/api/stays/:id/move', async (ctx) => { calls.push({ method: 'POST', url: new URL(ctx.request.url).pathname, body: await ctx.request.json() }); return HttpResponse.json({ rooms: [] }, { headers: { 'X-Audit-Event-Id': '77' } }); })]);
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    const before = planRequests.length;
    dragTo(bar('stay:813'), bedCells(22));
    await waitCalls(1);
    expect(calls[0]).toMatchObject({ url: '/api/stays/813/move', body: { targetBedId: 22 } });
    expect((calls[0].body as { fromDate?: string }).fromDate).toBeUndefined();
    // dotknuté izby (zdrojová aj cieľová – tu tá istá izba 2) sa dotiahnu znova
    await waitFor(() => expect(planRequests.length).toBeGreaterThan(before));
    expect(planRequests.at(-1)?.searchParams.getAll('roomIds')).toEqual(['2']);
  });

  it('Alt + ťahanie presunie pobyt od dňa pod kurzorom', async () => {
    mockServer([http.post('/api/stays/:id/move', async (ctx) => { calls.push({ method: 'POST', url: ctx.request.url, body: await ctx.request.json() }); return HttpResponse.json({ rooms: [] }); })]);
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    // stĺpec 4 (36 px/deň): x = 100 + 4 × 36 + 10 → 21. 11.
    dragTo(bar('stay:812'), bedCells(12), { alt: true, dx: 4 * 36 + 10 });
    await waitCalls(1);
    expect(calls[0].body).toEqual({ targetBedId: 12, fromDate: '2026-11-21' });
  });

  it('ťahanie pravého okraja zmení koniec pobytu (PATCH s verziou)', async () => {
    mockServer([http.patch('/api/stays/:id', async (ctx) => { calls.push({ method: 'PATCH', url: ctx.request.url, body: await ctx.request.json() }); return HttpResponse.json({ rooms: [] }); })]);
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    const handle = screen.getAllByTestId('resize-handle').find((h) => h.getAttribute('data-bar-id') === 'stay:813') as HTMLElement;
    fireEvent.pointerDown(handle, { pointerId: 1, button: 0, isPrimary: true, clientX: 200, clientY: 10 });
    for (const cx of [210, 272]) fireEvent.pointerMove(document, { pointerId: 1, isPrimary: true, clientX: cx, clientY: 10 });
    fireEvent.pointerUp(document, { pointerId: 1, isPrimary: true, clientX: 272, clientY: 10 });
    await waitCalls(1);
    // +72 px = +2 dni: 23. 11. → 25. 11.; verzia z pobytov karty
    expect(calls[0].body).toEqual({ to: '2026-11-25', version: 7 });
  });

  it('presun z kontextového menu „Presťahovať od…“', async () => {
    mockServer([http.post('/api/stays/:id/move', async (ctx) => { calls.push({ method: 'POST', url: ctx.request.url, body: await ctx.request.json() }); return HttpResponse.json({ rooms: [] }); })]);
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    fireEvent.contextMenu(bar('stay:812'));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Presťahovať od…' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.selectOptions(within(dialog).getByLabelText('Cieľové lôžko'), within(dialog).getByRole('option', { name: /102 · L2/ }));
    fireEvent.change(within(dialog).getByLabelText('Od dňa'), { target: { value: '2026-11-21' } });
    await userEvent.click(within(dialog).getByRole('button', { name: 'Presťahovať' }));
    await waitCalls(1);
    expect(calls[0].body).toEqual({ targetBedId: 22, fromDate: '2026-11-21' });
  });

  it('výmena: kontextové menu „Vymeniť s…“ a klik na druhý pásik', async () => {
    mockServer();
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    fireEvent.contextMenu(bar('stay:813'));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Vymeniť s…' }));
    expect(screen.getByTestId('plan-swap-hint')).toBeInTheDocument();
    await userEvent.click(bar('stay:814'));
    await waitCalls(1);
    expect(calls[0]).toMatchObject({ url: '/api/stays/swap', body: { stayIdA: 813, stayIdB: 814 } });
    expect(screen.queryByTestId('plan-swap-hint')).toBeNull();
  });

  it('zrušenie budúceho ubytovania po potvrdení; prebiehajúci pobyt túto akciu nemá', async () => {
    mockServer();
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    fireEvent.contextMenu(bar('stay:812'));
    expect(screen.queryByRole('menuitem', { name: 'Zrušiť ubytovanie' })).toBeNull();
    await userEvent.keyboard('{Escape}');
    fireEvent.contextMenu(bar('stay:813'));
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Zrušiť ubytovanie' }));
    await userEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Zrušiť ubytovanie' }));
    await waitCalls(1);
    expect(calls[0]).toMatchObject({ method: 'DELETE', url: '/api/stays/813' });
  });

  it('počas ťahania vyznačí, či je cieľ podľa lokálnych dát voľný', async () => {
    mockServer();
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    const start = (target: HTMLElement) => {
      setRect(target, 100, 100, 540, 30);
      fireEvent.pointerDown(bar('stay:813'), { pointerId: 1, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
      for (const cx of [20, 300]) fireEvent.pointerMove(document, { pointerId: 1, isPrimary: true, clientX: cx, clientY: 115 });
    };
    start(bedCells(11)); // 19.–22. 11. obsadené pobytom 812 (20.–23. sa prekrýva)
    expect((await screen.findByTestId('drop-hint')).getAttribute('data-free')).toBe('false');
    fireEvent.pointerUp(document, { pointerId: 1, isPrimary: true, clientX: 300, clientY: 115 });
    await waitFor(() => expect(screen.queryByTestId('drop-hint')).toBeNull());
    await userEvent.click(document.body);
    calls = [];
  });

  it('pobyt na lôžku 22 (obsadené iným pobytom) vs. voľné lôžko 12: hint podľa lokálnych dát', async () => {
    mockServer();
    renderInRouter(<PlanPage me={mover} />);
    await screen.findAllByTestId('plan-bar');
    setRect(bedCells(31), 100, 100, 540, 30);
    fireEvent.pointerDown(bar('stay:813'), { pointerId: 1, button: 0, isPrimary: true, clientX: 10, clientY: 10 });
    for (const cx of [20, 300]) fireEvent.pointerMove(document, { pointerId: 1, isPrimary: true, clientX: cx, clientY: 115 });
    // lôžko 31 je v izbe s blokáciou až od 25. 11., pobyt 813 trvá 20.–23. 11. → voľné
    expect((await screen.findByTestId('drop-hint')).getAttribute('data-free')).toBe('true');
    fireEvent.pointerUp(document, { pointerId: 1, isPrimary: true, clientX: 300, clientY: 115 });
  });

  describe('odkladisko a dávka', () => {
    async function parkTwo() {
      renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      dragTo(bar('stay:813'), screen.getByTestId('parking'));
      await waitFor(() => expect(screen.getAllByTestId('parked-item')).toHaveLength(1));
      dragTo(bar('stay:814'), screen.getByTestId('parking'));
      await waitFor(() => expect(screen.getAllByTestId('parked-item')).toHaveLength(2));
    }
    const items = () => screen.getAllByTestId('parked-item');

    it('pásiky sa z plánu skryjú, uložiť je možné až keď má každá položka cieľ; dávka ide jedným volaním', async () => {
      mockServer([http.post('/api/stays/moves:batch', async (ctx) => { calls.push({ method: 'POST', url: ctx.request.url.replace(/^.*\/api/, '/api'), body: await ctx.request.json() }); return HttpResponse.json({ rooms: [{ roomId: 1 }] }, { headers: { 'X-Audit-Event-Id': '9' } }); })]);
      await parkTwo();
      expect(screen.getAllByTestId('plan-bar').some((b) => b.getAttribute('data-bar-id') === 'stay:813')).toBe(false);
      const save = screen.getByRole('button', { name: 'Uložiť zmeny' });
      expect(save).toBeDisabled();

      dragTo(items()[0], bedCells(12)); // 813 → lôžko 12
      dragTo(items()[1], bedCells(21)); // 814 → lôžko 21 (uvoľnené odložením 813)
      await waitFor(() => expect(save).toBeEnabled());
      await userEvent.click(save);
      await waitCalls(1);
      expect(calls[0].url).toBe('/api/stays/moves:batch');
      expect(calls[0].body).toEqual([{ stayId: 813, targetBedId: 12 }, { stayId: 814, targetBedId: 21 }]);
      await waitFor(() => expect(screen.queryAllByTestId('parked-item')).toHaveLength(0)); // po úspechu sa odkladisko vyprázdni
    });

    it('chyba dávky ukáže, ktorá položka zlyhala, a odkladisko ostane', async () => {
      mockServer([http.post('/api/stays/moves:batch', () => problem(400, 'VALIDATION_FAILED', { detail: 'Pobyt 814 už je na tomto lôžku.' }))]);
      await parkTwo();
      dragTo(items()[0], bedCells(12));
      dragTo(items()[1], bedCells(21));
      const save = screen.getByRole('button', { name: 'Uložiť zmeny' });
      await waitFor(() => expect(save).toBeEnabled());
      await userEvent.click(save);
      expect(await screen.findByTestId('plan-notice')).toHaveTextContent('Zadané údaje nie sú platné.');
      expect(await screen.findByTestId('parked-failed')).toBeInTheDocument();
      expect(items()[1]).toHaveAttribute('data-failed', 'true');
      expect(items()).toHaveLength(2);
    });

    it('offline ostane odkladisko, ale „Uložiť zmeny“ je neaktívne', async () => {
      mockServer();
      await parkTwo();
      dragTo(items()[0], bedCells(12));
      dragTo(items()[1], bedCells(21));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Uložiť zmeny' })).toBeEnabled());
      act(() => connection.reset('offline'));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Uložiť zmeny' })).toBeDisabled());
    });
  });

  describe('chyby servera', () => {
    it('potvrditeľné upozornenie: dialóg, „Pokračovať“ pošle tú istú požiadavku s confirm', async () => {
      let n = 0;
      mockServer([
        http.post('/api/stays/:id/move', async ({ request }) => {
          const body = (await request.json()) as { confirm?: string[] };
          calls.push({ method: 'POST', url: request.url, body });
          return n++ === 0 ? problem(409, 'CONFIRMATION_REQUIRED', { confirmable: true, warnings: ['RELEASES_HELD_BED'] }) : HttpResponse.json({ rooms: [] });
        }),
      ]);
      renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      dragTo(bar('stay:813'), bedCells(22));
      expect(await screen.findByTestId('confirm-warnings')).toHaveTextContent('Ubytovaním sa uvoľní lôžko držané pre iný pobyt.');
      await userEvent.click(screen.getByRole('button', { name: 'Pokračovať' }));
      await waitCalls(2);
      expect(calls[1].body).toEqual({ targetBedId: 22, confirm: ['RELEASES_HELD_BED'] });
      await waitFor(() => expect(screen.queryByTestId('confirm-warnings')).toBeNull());
    });

    it('409 konflikt verzie: upozornenie, obnovenie izieb, požiadavka sa nezopakuje', async () => {
      mockServer([http.post('/api/stays/:id/move', async ({ request }) => { calls.push({ method: 'POST', url: request.url, body: await request.json() }); return problem(409, 'STALE_DATA'); })]);
      renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      const before = planRequests.length;
      dragTo(bar('stay:813'), bedCells(22));
      expect(await screen.findByTestId('plan-notice')).toHaveTextContent('Dotknuté izby sa obnovili.');
      await waitFor(() => expect(planRequests.length).toBeGreaterThan(before));
      expect(calls).toHaveLength(1);
    });

    it('409 obsadené lôžko: upozornenie s textom chyby', async () => {
      mockServer([http.post('/api/stays/:id/move', () => problem(409, 'BED_OCCUPIED'))]);
      renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      dragTo(bar('stay:813'), bedCells(22));
      expect(await screen.findByTestId('plan-notice')).toHaveTextContent('Lôžko je v tomto termíne už obsadené.');
    });
  });

  describe('oprávnenia a offline', () => {
    it('bez STAY_MOVE sa pásiky nedajú ťahať, nie je menu ani odkladisko', async () => {
      mockServer();
      renderInRouter(<PlanPage me={receptionist} />);
      await screen.findAllByTestId('plan-bar');
      expect(screen.queryByTestId('parking')).toBeNull();
      expect(screen.queryAllByTestId('resize-handle')).toHaveLength(0);
      fireEvent.contextMenu(bar('stay:813'));
      expect(screen.queryByTestId('bar-menu')).toBeNull();
    });

    it('zamknutý pobyt sa hýbe len s STAY_MOVE_LOCKED', async () => {
      const locked = () => {
        const p = plan();
        (p.rooms?.[1].bars?.find((b) => (b as { stayId?: number }).stayId === 813) as { locked: boolean }).locked = true;
        return p;
      };
      mockServer([http.get('/api/accommodation-plan', () => HttpResponse.json(locked()))]);
      const { unmount } = renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      fireEvent.contextMenu(bar('stay:813'));
      expect(screen.queryByTestId('bar-menu')).toBeNull();
      unmount();

      mockServer([http.get('/api/accommodation-plan', () => HttpResponse.json(locked()))]);
      renderInRouter(<PlanPage me={{ ...mover, permissions: [...(mover.permissions ?? []), { code: 'STAY_MOVE_LOCKED' }] }} />);
      await screen.findAllByTestId('plan-bar');
      fireEvent.contextMenu(bar('stay:813'));
      expect(await screen.findByTestId('bar-menu')).toBeInTheDocument();
    });

    it('spätná zmena (pobyt začínajúci pred obchodným dňom) bez STAY_BACKDATE: odkladisko ju odmietne', async () => {
      const past = () => {
        const p = plan();
        p.rooms?.[1].bars?.push(stayBar(815, 21, '2026-11-17', '2026-11-19', 'Minulý D.', 'RESERVED'));
        return p;
      };
      mockServer([http.get('/api/accommodation-plan', () => HttpResponse.json(past()))]);
      renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      dragTo(bar('stay:815'), bedCells(22));
      expect(await screen.findByTestId('plan-notice')).toHaveTextContent('Tento presun nie je povolený.');
      expect(calls).toHaveLength(0);
    });

    it('offline sa ťahanie ani menu neponúka', async () => {
      mockServer();
      connection.reset('offline');
      renderInRouter(<PlanPage me={mover} />);
      // bez uložených dát v trezore nie je čo ukázať; pri online → offline prechode ostane plán len na čítanie
      connection.reset('online');
      renderInRouter(<PlanPage me={mover} />);
      await screen.findAllByTestId('plan-bar');
      act(() => connection.reset('offline'));
      await waitFor(() => expect(screen.queryAllByTestId('resize-handle')).toHaveLength(0));
      expect(screen.queryByTestId('parking')).toBeNull();
      fireEvent.contextMenu(bar('stay:813'));
      expect(screen.queryByTestId('bar-menu')).toBeNull();
    });
  });
});
