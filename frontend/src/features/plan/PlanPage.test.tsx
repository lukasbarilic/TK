import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { resetHandledEvents, markEventHandled } from '../../shared/offline/handledEvents';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { PlanPage } from './PlanPage';
import { BUSINESS_DATE, sampleCard, samplePlan, stayBar, WINDOW } from './testData';
import type { Plan } from './types';

let planRequests: URL[] = [];
let preferenceWrites: { key: string; body: { value: { zoom?: string } } }[] = [];

function mockServer(plan: () => Plan = samplePlan) {
  planRequests = [];
  preferenceWrites = [];
  server.use(
    http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, code: 'R2', name: 'Recepcia B', active: true }])),
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: BUSINESS_DATE })),
    http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
    http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
    http.put('/api/me/preferences/:key', async ({ request, params }) => {
      const body = (await request.json()) as { value: { zoom?: string } };
      preferenceWrites.push({ key: String(params.key), body });
      return HttpResponse.json({ key: params.key, value: body.value, version: 1 });
    }),
    http.get('/api/accommodation-plan', ({ request }) => {
      const url = new URL(request.url);
      planRequests.push(url);
      const ids = url.searchParams.getAll('roomIds').map(Number);
      const p = plan();
      return HttpResponse.json(ids.length ? { ...p, rooms: p.rooms?.filter((r) => ids.includes(r.roomId as number)) } : p);
    }),
    http.get('/api/reservations/:id', () => HttpResponse.json(sampleCard)),
  );
}

const roomRows = () => screen.getAllByTestId('plan-room-row').map((r) => r.getAttribute('data-room-id'));
const bar = (id: string, bedId?: number) =>
  screen
    .getAllByTestId('plan-bar')
    .find((b) => b.getAttribute('data-bar-id') === id && (bedId === undefined || b.closest('[data-bed-id]')?.getAttribute('data-bed-id') === String(bedId)));

beforeEach(async () => {
  resetHandledEvents();
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('PlanPage – online', () => {
  it('pásiky ležia na správnych stĺpcoch; víkendy, čiara obchodného dňa', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');

    // okno 17. 11. – 1. 12. (obchodný deň −1 až +13): 19. 11. = stĺpec 2, 22. 11. = stĺpec 5
    const stay = bar('stay:812', 11);
    expect(stay).toHaveAttribute('data-start-col', '2');
    expect(stay).toHaveAttribute('data-end-col', '5');
    expect(stay).toHaveTextContent('Testovský A.');
    // pásik začína v polovici dňa príchodu: 36 px × 2 + 18 px
    expect(stay).toHaveStyle({ left: '90px', width: '108px' });

    expect(bar('held:812:12', 12)).toHaveAttribute('data-start-col', '2');
    // blokácia 25. 11. – 28. 11. = stĺpce 8–11, jediné lôžko izby 201
    expect(bar('block:5', 31)).toHaveAttribute('data-start-col', '8');
    expect(bar('block:5', 31)).toHaveAttribute('data-end-col', '11');

    expect(screen.getByTestId('business-day-line')).toHaveAttribute('data-col', '1');
    // 21., 22., 28., 29. 11. = víkendy v okne
    expect(screen.getAllByTestId('plan-weekend').map((w) => w.getAttribute('data-day'))).toEqual(['2026-11-21', '2026-11-22', '2026-11-28', '2026-11-29']);
    expect(screen.getAllByTestId('plan-day')).toHaveLength(15);
    expect(screen.queryByTestId('plan-no-data')).toBeNull();
    // online: bez „Údaje z“
    expect(screen.queryByTestId('data-age')).toBeNull();
  });

  it('prístelka je v zozname lôžok izby', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    expect(screen.getByText('Prístelka 3')).toBeInTheDocument();
  });

  it('filter freeOnly skryje obsadené a blokované izby', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    expect(roomRows()).toEqual(['1', '2', '3']);

    await userEvent.click(screen.getByRole('switch', { name: 'Len voľné izby' }));
    // 101: obe riadne lôžka obsadené/držané; 201: blokácia v rozsahu → ostáva len 102
    await waitFor(() => expect(roomRows()).toEqual(['2']));
    // a fullyFree
    await userEvent.click(screen.getByRole('switch', { name: 'Len voľné izby' }));
    await userEvent.click(screen.getByRole('switch', { name: 'Len celkom prázdne izby' }));
    await waitFor(() => expect(roomRows()).toEqual(['2']));
  });

  it('filter budov', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    await userEvent.click(screen.getByRole('button', { name: 'VIL' }));
    await waitFor(() => expect(roomRows()).toEqual(['3']));
  });

  it('klik na pásik otvorí bočný panel s pobytom a kartou', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    await userEvent.click(bar('stay:812', 11) as HTMLElement);
    const panel = await screen.findByTestId('stay-panel');
    expect(within(panel).getByText('Izba ALF 101, lôžko 1')).toBeInTheDocument();
    expect(within(panel).getByTestId('panel-dates')).toHaveTextContent('2026-11-19 – 2026-11-22');
    expect(await within(panel).findByText('R-2026-0912')).toBeInTheDocument();
    expect(within(panel).getByText(/Testovská Anna/)).toBeInTheDocument();
    await userEvent.click(within(panel).getByRole('button', { name: 'Zavrieť' }));
    expect(screen.queryByTestId('stay-panel')).toBeNull();
  });

  it('zmena pohľadu sa uloží do plan.defaultView', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    expect(preferenceWrites).toEqual([]);
    await userEvent.click(screen.getByText('Široké'));
    await waitFor(() => expect(preferenceWrites).toHaveLength(1), { timeout: 2000 });
    expect(preferenceWrites[0].key).toBe('plan.defaultView');
    expect(preferenceWrites[0].body.value.zoom).toBe('wide');
    // pásik je pri šírke 56 px širší: 3 dni = 168 px
    expect(bar('stay:812', 11)).toHaveStyle({ width: '168px' });
  });

  it('odkaz z karty na vzdialený dátum posunie rozsah, ale neuloží ho do plan.defaultView', async () => {
    mockServer();
    renderWithProviders(<PlanPage me={receptionist} focus={{ date: '2027-01-27' }} />);
    await waitFor(() => expect(screen.getByTestId('plan-range')).toHaveTextContent('2027-01-26 – 2027-02-09'));
    // načíta sa len posunutý rozsah, nie spolu s oknom okolo obchodného dňa
    await waitFor(() =>
      expect(planRequests.some((u) => u.searchParams.get('from') === '2027-01-26' && u.searchParams.get('to') === '2027-02-09')).toBe(true),
    );
    await new Promise((r) => setTimeout(r, 600));
    expect(preferenceWrites).toEqual([]);
    // „Dnes“ vráti rozsah k obchodnému dňu
    await userEvent.click(screen.getByText('Dnes'));
    await waitFor(() => expect(screen.getByTestId('plan-range')).toHaveTextContent('2026-11-17 – 2026-12-01'));
  });

  it('posledný pohľad sa načíta z plan.defaultView a farby z plan.colors', async () => {
    mockServer();
    server.use(
      http.get('/api/me/preferences/plan.defaultView', () =>
        HttpResponse.json({ key: 'plan.defaultView', version: 3, value: { freeOnly: true, zoom: 'narrow', buildingIds: [], daysBack: 1, daysAhead: 13 } }),
      ),
      http.get('/api/me/preferences/plan.colors', () =>
        HttpResponse.json({ key: 'plan.colors', version: 1, value: { CHECKED_IN: '#00AA00' } }),
      ),
    );
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findByTestId('plan-grid');
    expect(screen.getByRole('switch', { name: 'Len voľné izby' })).toBeChecked();
    expect(roomRows()).toEqual(['2']);
    await userEvent.click(screen.getByRole('switch', { name: 'Len voľné izby' })); // zobrazí obsadenú izbu
    await waitFor(() => expect(bar('stay:812', 11)).toBeDefined());
    expect(bar('stay:812', 11)).toHaveStyle({ background: 'rgb(0, 170, 0)', width: '72px' }); // úzky zoom: 24 px × 3
  });

  describe('živé zmeny', () => {
    const event = (data: Record<string, unknown>) =>
      act(() => connection.onLive({ type: 'plan-changed', data: { receptionId: 2, roomIds: [1], from: '2026-11-19', to: '2026-11-22', ...data } }));

    it('plan-changed obnoví len dotknuté izby a vlastná zmena sa nespracuje dvakrát', async () => {
      let current = samplePlan();
      mockServer(() => current);
      renderWithProviders(<PlanPage me={receptionist} />);
      await screen.findAllByTestId('plan-bar');
      expect(planRequests).toHaveLength(1);
      expect(bar('block:5', 31)).toBeDefined();

      // iný používateľ presunul hosťa; zmena aj blokácie v izbe 3, ktorá nie je v udalosti, sa ešte nesmie prejaviť
      current = samplePlan();
      current.rooms![0].bars = [stayBar(812, 11, '2026-11-20', '2026-11-23', 'Zmenený B.')];
      current.rooms![2].bars = [];
      event({ auditEventId: 77 });

      await waitFor(() => expect(bar('stay:812', 11)).toHaveTextContent('Zmenený B.'));
      expect(bar('stay:812', 11)).toHaveAttribute('data-start-col', '3');
      expect(planRequests).toHaveLength(2);
      expect(planRequests[1].searchParams.getAll('roomIds')).toEqual(['1']); // len dotknutá izba
      expect(bar('block:5', 31)).toBeDefined(); // izba 3 nebola obnovená

      // rovnaký auditEventId = už spracované
      event({ auditEventId: 77 });
      // vlastná zmena (zaznačená po odpovedi zápisu) sa nespracuje
      markEventHandled(78);
      event({ auditEventId: 78 });
      await new Promise((r) => setTimeout(r, 50));
      expect(planRequests).toHaveLength(2);
    });

    it('udalosť inej recepcie alebo mimo okna sa ignoruje', async () => {
      mockServer();
      renderWithProviders(<PlanPage me={receptionist} />);
      await screen.findAllByTestId('plan-bar');
      event({ auditEventId: 1, receptionId: 1 });
      event({ auditEventId: 2, from: '2027-03-01', to: '2027-03-05' });
      await new Promise((r) => setTimeout(r, 50));
      expect(planRequests).toHaveLength(1);
    });

    it('business-day-changed obnoví celý plán', async () => {
      let current = samplePlan();
      mockServer(() => current);
      renderWithProviders(<PlanPage me={receptionist} />);
      await screen.findAllByTestId('plan-bar');
      current = samplePlan();
      current.rooms![2].bars = [];
      act(() => connection.onLive({ type: 'business-day-changed', data: { receptionId: 2 } }));
      await waitFor(() => expect(bar('block:5')).toBeUndefined());
      expect(planRequests.length).toBeGreaterThanOrEqual(2);
      expect(planRequests.every((u) => u.searchParams.getAll('roomIds').length === 0)).toBe(true);
    });
  });
});

describe('PlanPage – offline', () => {
  async function seed(window = WINDOW) {
    const syncedAt = '2026-11-17T21:47:00.000Z';
    await platform.vault.put(DS.catalog, 'receptions', [{ id: 2, code: 'R2', name: 'Recepcia B', active: true }], { syncedAt });
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: BUSINESS_DATE }, { syncedAt });
    await platform.vault.put(DS.plan, '2', { ...window, plan: samplePlan() }, { syncedAt });
    await platform.vault.put(DS.reservation, '912', sampleCard, { syncedAt });
  }

  it('zobrazí plán z trezora s vekom dát a režimom len na čítanie; karta z trezora', async () => {
    await seed();
    connection.reset('offline');
    renderWithProviders(<PlanPage me={receptionist} />); // žiadny HTTP handler: server sa nesmie volať
    await screen.findAllByTestId('plan-bar');
    expect(bar('stay:812', 11)).toHaveAttribute('data-start-col', '2');
    expect(screen.getByTestId('data-age')).toHaveTextContent(/Údaje z/);
    expect(screen.getByTestId('plan-readonly')).toBeInTheDocument();

    await userEvent.click(bar('stay:812', 11) as HTMLElement);
    const panel = await screen.findByTestId('stay-panel');
    expect(await within(panel).findByText('R-2026-0912')).toBeInTheDocument();
    expect(within(panel).getByTestId('data-age')).toBeInTheDocument();
  });

  it('dni mimo lokálnych dát sú šrafované s textom „Údaje nie sú k dispozícii“', async () => {
    await seed({ from: '2026-11-17', to: '2026-11-25' });
    connection.reset('offline');
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    const hatched = screen.getByTestId('plan-no-data');
    expect(hatched).toHaveTextContent('Údaje nie sú k dispozícii');
    // 26. 11. – 1. 12. = stĺpce 9–14: 6 dní × 36 px
    expect(hatched).toHaveStyle({ left: '324px', width: '216px' });
  });

  it('bez dát v trezore ukáže, že údaje nie sú uložené', async () => {
    await platform.vault.put(DS.catalog, 'receptions', [{ id: 2, name: 'Recepcia B', active: true }], { syncedAt: '2026-11-17T21:47:00.000Z' });
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: BUSINESS_DATE }, { syncedAt: '2026-11-17T21:47:00.000Z' });
    connection.reset('offline');
    renderWithProviders(<PlanPage me={receptionist} />);
    expect(await screen.findByTestId('plan-empty')).toHaveTextContent('Tieto údaje nie sú na tomto počítači uložené');
  });

  it('zmena pohľadu offline sa uloží len lokálne', async () => {
    await seed();
    connection.reset('offline');
    renderWithProviders(<PlanPage me={receptionist} />);
    await screen.findAllByTestId('plan-bar');
    await userEvent.click(screen.getByText('Široké'));
    await waitFor(async () => expect((await platform.vault.get<{ value: { zoom: string } }>(DS.preferences, 'plan.defaultView'))?.value.value.zoom).toBe('wide'), { timeout: 2000 });
  });
});
