import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { connection } from '../shared/offline';
import { DS } from '../shared/offline/datasets';
import { platform } from '../shared/platform';
import { receptionist } from '../shared/test/handlers';
import { renderWithProviders } from '../shared/test/render';
import { server } from '../shared/test/server';
import { MovementsPage } from '../features/movements';
import { PlanPage } from '../features/plan';
import { BUSINESS_DATE, samplePlan, stayBar } from '../features/plan/testData';
import { ReservationDetailPage } from '../features/reservations';
import { CheckInDialog } from '../features/stays';

const withPerms = (...codes: string[]): typeof receptionist => ({
  ...receptionist,
  permissions: [...(receptionist.permissions ?? []), ...codes.map((code) => ({ code, scopes: [{ receptionId: 2 }] }))],
});
const clerk = withPerms('RESERVATION_STATUS');
const supervisor = withPerms('RESERVATION_STATUS', 'RESERVATION_STATUS_FIX');

type Status = 'RESERVED' | 'CHECKED_IN' | 'CHECKED_OUT' | 'CANCELLED' | 'NO_SHOW';
const cardOf = (status: Status, extra: object = {}) => ({
  id: 912, reservationNumber: 'R-2026-0912', status, arrivalDate: '2026-11-18', departureDate: '2026-11-25', version: 3, bookedCategoryId: 2,
  guests: [{ id: 1, firstName: 'Anna', lastName: 'Testovská', primaryGuest: true }], ...extra,
});

let current = cardOf('RESERVED');
let posts: { url: string; body: Record<string, unknown> }[] = [];
let planFetches = 0;
let movementFetches = 0;

const problem = (status: number, code: string, extra: object = {}) => HttpResponse.json({ status, code, title: code, ...extra }, { status });

/** `next` = stav karty po úspešnej akcii; `fail` = odpoveď servera namiesto úspechu. */
function mockServer(o: { next?: Status; fail?: (n: number, body: Record<string, unknown>) => Response | undefined } = {}) {
  posts = [];
  planFetches = 0;
  movementFetches = 0;
  const action = (path: string, next: Status) =>
    http.post(`/api/reservations/:id/${path}`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      posts.push({ url: path, body });
      const failure = o.fail?.(posts.length, body);
      if (failure) return failure;
      current = { ...current, status: next, version: current.version + 1 };
      return HttpResponse.json(current);
    });
  server.use(
    http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, code: 'R2', name: 'Recepcia B', active: true }])),
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: BUSINESS_DATE })),
    http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
    http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
    http.get('/api/reservations/:id', () => HttpResponse.json(current)),
    http.get('/api/reservations/:id/stays', () => HttpResponse.json([])),
    action('check-in', o.next ?? 'CHECKED_IN'),
    action('check-out', o.next ?? 'CHECKED_OUT'),
    action('cancel', 'CANCELLED'),
    action('no-show', 'NO_SHOW'),
    action('undo-check-in', 'RESERVED'),
    action('revert-no-show', 'RESERVED'),
  );
}

beforeEach(async () => {
  current = cardOf('RESERVED');
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

const renderCard = (me = clerk) =>
  renderWithProviders(
    <MemoryRouter>
      <ReservationDetailPage id={912} me={me} canSeeContacts />
    </MemoryRouter>,
  );

describe('Stavy karty: detail', () => {
  it('RESERVED: odznak, tri kroky a príchod hosťa pošle verziu karty a obnoví kartu', async () => {
    mockServer();
    renderCard();
    expect(await screen.findByRole('button', { name: 'Príchod hosťa' })).toBeInTheDocument();
    expect(screen.getAllByTestId('status-step').map((s) => s.textContent)).toEqual(['1. Rezervovaná', '2. Prítomný', '3. Odhlásený']);
    expect(screen.getByRole('button', { name: 'Neprišiel' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Storno…' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Zrušiť príchod' })).toBeNull();

    await userEvent.click(screen.getByRole('button', { name: 'Príchod hosťa' }));
    await waitFor(() => expect(posts).toEqual([{ url: 'check-in', body: { version: 3 } }]));
    // po akcii sa karta načíta znova: ponuka sa zmení na odchod
    expect(await screen.findByRole('button', { name: 'Odchod hosťa' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Príchod hosťa' })).toBeNull();
  });

  it('storno vyžaduje dôvod a pošle ho', async () => {
    mockServer();
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Storno…' }));
    const dialog = await screen.findByRole('dialog');
    const submit = within(dialog).getByRole('button', { name: 'Stornovať kartu' });
    expect(submit).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText('Dôvod storna'), 'Klient ochorel');
    await userEvent.click(submit);
    await waitFor(() => expect(posts).toEqual([{ url: 'cancel', body: { version: 3, reason: 'Klient ochorel' } }]));
    expect(await screen.findByTestId('status-cancelled')).toBeInTheDocument();
  });

  it('neprišiel pošle no-show a potom ponúkne len neskorý príchod (s opravným oprávnením)', async () => {
    mockServer();
    renderCard(supervisor);
    await userEvent.click(await screen.findByRole('button', { name: 'Neprišiel' }));
    await waitFor(() => expect(posts).toEqual([{ url: 'no-show', body: { version: 3 } }]));
    await userEvent.click(await screen.findByRole('button', { name: 'Neskorý príchod' }));
    await waitFor(() => expect(posts.at(-1)).toEqual({ url: 'revert-no-show', body: { version: 4 } }));
  });

  it('zrušiť príchod len s oprávnením RESERVATION_STATUS_FIX', async () => {
    current = cardOf('CHECKED_IN', { checkedInAt: '2026-11-18T08:30:00Z' });
    mockServer();
    const first = renderCard(clerk);
    expect(await screen.findByRole('button', { name: 'Odchod hosťa' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Zrušiť príchod' })).toBeNull();
    first.unmount();

    renderCard(supervisor);
    await userEvent.click(await screen.findByRole('button', { name: 'Zrušiť príchod' }));
    await waitFor(() => expect(posts).toEqual([{ url: 'undo-check-in', body: { version: 3 } }]));
  });

  it('bez oprávnenia RESERVATION_STATUS sa tlačidlá stavu neukážu', async () => {
    mockServer();
    renderCard(receptionist);
    await screen.findByTestId('reservation-number');
    expect(screen.queryByTestId('status-buttons')).toBeNull();
  });

  it('skorší odchod: upozornenie sa potvrdí cez dialóg a požiadavka ide znova s confirm', async () => {
    current = cardOf('CHECKED_IN');
    mockServer({
      fail: (n) => (n === 1 ? problem(409, 'CONFIRMATION_REQUIRED', { confirmable: true, warnings: [{ code: 'EARLY_DEPARTURE', message: 'x' }] }) : undefined),
    });
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Odchod hosťa' }));
    expect(await screen.findByTestId('confirm-warnings')).toHaveTextContent('Hosť odchádza skôr');
    await userEvent.click(screen.getByRole('button', { name: 'Pokračovať' }));
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[1]).toEqual({ url: 'check-out', body: { version: 3, confirm: ['EARLY_DEPARTURE'] } });
  });

  it.each([
    ['NO_BED_ASSIGNED', 'Karta nemá pridelené lôžko pre všetkých hostí. Najprv prideľte lôžko.'],
    ['ARRIVAL_IN_FUTURE', 'Dátum príchodu je neskôr ako obchodný deň, hosťa zatiaľ nemožno prijať.'],
    ['INVALID_STATUS_TRANSITION', 'Z aktuálneho stavu karty túto akciu nemožno vykonať. Údaje sa obnovili.'],
  ])('chyba %s má slovenský text', async (code, text) => {
    mockServer({ fail: () => problem(409, code) });
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Príchod hosťa' }));
    expect(await screen.findByTestId('status-error')).toHaveTextContent(text);
  });

  it('bez spojenia sú akcie stavu neaktívne', async () => {
    mockServer();
    await platform.vault.put(DS.reservation, '912', current, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderCard();
    const button = await screen.findByRole('button', { name: 'Príchod hosťa' });
    expect(button).toBeDisabled();
    expect(button.closest('[data-testid="write-disabled"]')).not.toBeNull();
  });
});

describe('Stavy karty: plán', () => {
  /** Plán: 812 prítomný (odchod dnes, poznámka), 813 rezervovaný s príchodom dnes, 814 neprišiel, 815 odhlásený. */
  const statusPlan = () => {
    const p = samplePlan();
    p.rooms![0].bars = [
      { ...stayBar(812, 11, '2026-11-15', BUSINESS_DATE, 'Odchodová A.', 'CHECKED_IN'), hasNote: true },
      stayBar(815, 12, '2026-11-17', '2026-11-19', 'Odhlásená D.', 'CHECKED_OUT'),
    ];
    p.rooms![1].bars = [
      stayBar(813, 21, BUSINESS_DATE, '2026-11-22', 'Príchodová B.', 'RESERVED'),
      stayBar(814, 22, '2026-11-17', '2026-11-19', 'Neprišla C.', 'NO_SHOW'),
    ];
    return p;
  };
  const renderPlan = (me = clerk) => {
    server.use(
      http.get('/api/accommodation-plan', () => {
        planFetches++;
        return HttpResponse.json(statusPlan());
      }),
    );
    return renderWithProviders(<PlanPage me={me} renderCheckIn={(target, onClose) => <CheckInDialog me={me} onClose={onClose} target={target} />} />);
  };
  const bar = (id: string) => screen.getAllByTestId('plan-bar').find((b) => b.getAttribute('data-bar-id') === id) as HTMLElement;

  it('farby pásikov podľa stavu karty, ikony a legenda', async () => {
    mockServer();
    renderPlan();
    await screen.findAllByTestId('plan-bar');
    expect(bar('stay:813')).toHaveAttribute('data-color-key', 'RESERVED');
    expect(bar('stay:813').style.border).toContain('dashed');
    expect(bar('stay:812')).toHaveAttribute('data-color-key', 'CHECKED_IN');
    expect(bar('stay:812').style.border).toContain('solid');
    expect(bar('stay:812').style.background).toBe('rgb(30, 95, 168)');
    expect(bar('stay:815')).toHaveAttribute('data-color-key', 'CHECKED_OUT');
    expect(bar('stay:814')).toHaveAttribute('data-color-key', 'NO_SHOW');

    expect(within(bar('stay:813')).getByTestId('bar-icon-arrival')).toBeInTheDocument();
    expect(within(bar('stay:812')).getByTestId('bar-icon-departure')).toBeInTheDocument();
    expect(within(bar('stay:812')).getByTestId('bar-icon-note')).toBeInTheDocument();
    expect(within(bar('stay:815')).queryByTestId('bar-icon-arrival')).toBeNull();
    expect(within(bar('stay:814')).queryByTestId('bar-icon-note')).toBeNull();

    const legend = screen.getByTestId('plan-legend');
    for (const text of ['Rezervovaná', 'Prítomný', 'Odhlásený', 'Neprišiel']) expect(legend).toHaveTextContent(text);
  });

  it('v hlavičke počty Dnes príde, Odíde a V dome', async () => {
    mockServer();
    renderPlan();
    await screen.findAllByTestId('plan-bar');
    expect(screen.getByTestId('count-arriving')).toHaveTextContent('1');
    expect(screen.getByTestId('count-departing')).toHaveTextContent('1');
    expect(screen.getByTestId('count-inhouse')).toHaveTextContent('1');
  });

  it('menu pravým tlačidlom: prvá je hlavná akcia dňa, Enter ju vykoná a plán sa obnoví', async () => {
    mockServer();
    renderPlan();
    await screen.findAllByTestId('plan-bar');
    const before = planFetches;
    fireEvent.contextMenu(bar('stay:813'));
    const items = within(await screen.findByTestId('bar-menu')).getAllByRole('menuitem').map((i) => i.textContent);
    expect(items).toEqual(['Príchod hosťa', 'Neprišiel', 'Storno…']);
    const first = screen.getAllByRole('menuitem')[0];
    await waitFor(() => expect(first).toHaveFocus());
    await userEvent.keyboard('{Enter}');
    await waitFor(() => expect(posts).toEqual([{ url: 'check-in', body: { version: 3 } }]));
    await waitFor(() => expect(planFetches).toBeGreaterThan(before));
    expect(screen.queryByTestId('bar-menu')).toBeNull();
  });

  it('klávesom Menu sa otvorí menu; prítomný hosť: Odchod hosťa, oprava len s oprávnením', async () => {
    mockServer();
    const first = renderPlan(clerk);
    await screen.findAllByTestId('plan-bar');
    fireEvent.keyDown(bar('stay:812'), { key: 'ContextMenu' });
    expect(within(await screen.findByTestId('bar-menu')).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Odchod hosťa']);
    first.unmount();

    renderPlan(supervisor);
    await screen.findAllByTestId('plan-bar');
    fireEvent.contextMenu(bar('stay:812'));
    expect(within(await screen.findByTestId('bar-menu')).getAllByRole('menuitem').map((i) => i.textContent)).toEqual(['Odchod hosťa', 'Zrušiť príchod']);
  });

  it('bez oprávnenia stavu a bez presunov sa menu neotvorí; offline tiež nie', async () => {
    mockServer();
    renderPlan(receptionist);
    await screen.findAllByTestId('plan-bar');
    fireEvent.contextMenu(bar('stay:813'));
    expect(screen.queryByTestId('bar-menu')).toBeNull();
  });

  it('offline v pláne nie sú akcie stavu', async () => {
    const syncedAt = '2026-11-18T08:00:00Z';
    await platform.vault.put(DS.catalog, 'receptions', [{ id: 2, code: 'R2', name: 'Recepcia B', active: true }], { syncedAt });
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: BUSINESS_DATE }, { syncedAt });
    await platform.vault.put(DS.plan, '2', { from: '2026-11-17', to: '2026-12-02', plan: statusPlan() }, { syncedAt });
    connection.reset('offline');
    renderWithProviders(<PlanPage me={clerk} />);
    await screen.findAllByTestId('plan-bar');
    expect(screen.getByTestId('plan-readonly')).toBeInTheDocument();
    fireEvent.contextMenu(bar('stay:813'));
    expect(screen.queryByTestId('bar-menu')).toBeNull();
  });
});

describe('Stavy karty: príchody a odchody', () => {
  const mv = (o: object) => ({ reservationNumber: 'R-2026-0001', guestCount: 1, arrivalDate: '2026-11-18', departureDate: '2026-11-22', status: 'RESERVED', ...o });
  const DAY = {
    receptionId: 2, date: '2026-11-18',
    arrivals: [
      mv({ reservationId: 912, guestName: 'Testovská A.', roomNumber: 'B 204' }),
      mv({ reservationId: 2, guestName: 'Prijatá M.', roomNumber: 'A 205', status: 'CHECKED_IN' }),
      mv({ reservationId: 3, guestName: 'Bez Izby' }),
    ],
    departures: [
      mv({ reservationId: 4, guestName: 'Odchádzajúci L.', roomNumber: 'A 108', status: 'CHECKED_IN' }),
      mv({ reservationId: 5, guestName: 'Odhlásený K.', roomNumber: 'A 109', status: 'CHECKED_OUT' }),
    ],
  };

  const renderMovements = (me = clerk, assign = true) => {
    server.use(
      http.get('/api/receptions/:id/movements', () => {
        movementFetches++;
        return HttpResponse.json(DAY);
      }),
    );
    return renderWithProviders(
      <MemoryRouter>
        <MovementsPage me={me} renderAssignBed={assign ? (c, onClose) => <div data-testid="assign-dialog" data-card={c.id}><button onClick={onClose}>zavrieť</button></div> : undefined} />
      </MemoryRouter>,
    );
  };
  const rowOf = (name: string) => screen.getAllByTestId('movement-row').find((r) => r.textContent?.includes(name)) as HTMLElement;

  it('počítadlá prijatých a odhlásených', async () => {
    mockServer();
    renderMovements();
    await screen.findByTestId('movements-arrivals');
    expect(screen.getByTestId('movements-arrivals-progress')).toHaveTextContent('prijatých 1 / 3');
    expect(screen.getByTestId('movements-departures-progress')).toHaveTextContent('odhlásených 1 / 2');
  });

  it('Príchod z riadku pošle check-in a obnoví zoznam', async () => {
    mockServer();
    renderMovements();
    await screen.findByTestId('movements-arrivals');
    const before = movementFetches;
    await userEvent.click(within(rowOf('Testovská')).getByRole('button', { name: 'Príchod' }));
    await waitFor(() => expect(posts).toEqual([{ url: 'check-in', body: { version: 3 } }]));
    await waitFor(() => expect(movementFetches).toBeGreaterThan(before));
  });

  it('Odchod z riadku pošle check-out; hotové riadky a karta bez lôžka majú správne tlačidlá', async () => {
    mockServer();
    renderMovements();
    await screen.findByTestId('movements-arrivals');
    expect(within(rowOf('Prijatá')).queryByRole('button')).toBeNull();
    expect(within(rowOf('Odhlásený K.')).queryByRole('button')).toBeNull();
    expect(within(rowOf('Bez Izby')).queryByRole('button', { name: 'Príchod' })).toBeNull();
    await userEvent.click(within(rowOf('Odchádzajúci')).getByRole('button', { name: 'Odchod' }));
    await waitFor(() => expect(posts).toEqual([{ url: 'check-out', body: { version: 3 } }]));
  });

  it('karta bez lôžka: Prideliť lôžko otvorí dialóg pridelenia', async () => {
    mockServer();
    renderMovements();
    await screen.findByTestId('movements-arrivals');
    await userEvent.click(within(rowOf('Bez Izby')).getByRole('button', { name: 'Prideliť lôžko' }));
    expect(await screen.findByTestId('assign-dialog')).toHaveAttribute('data-card', '3');
    expect(posts).toEqual([]);
  });

  it('chyba NO_BED_ASSIGNED z príchodu sa ukáže po slovensky', async () => {
    mockServer({ fail: () => problem(409, 'NO_BED_ASSIGNED') });
    renderMovements();
    await screen.findByTestId('movements-arrivals');
    await userEvent.click(within(rowOf('Testovská')).getByRole('button', { name: 'Príchod' }));
    expect(await screen.findByTestId('status-error')).toHaveTextContent('Karta nemá pridelené lôžko');
  });

  it('bez oprávnenia nie sú tlačidlá; offline sú neaktívne', async () => {
    mockServer();
    const first = renderMovements(receptionist);
    await screen.findByTestId('movements-arrivals');
    expect(within(rowOf('Testovská')).queryByRole('button')).toBeNull();
    first.unmount();

    await platform.vault.put(DS.catalog, 'receptions', [{ id: 2, code: 'R2', name: 'Recepcia B', active: true }], { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: '2026-11-18' }, { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.movements, '2:2026-11-18', DAY, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderMovements(clerk);
    await waitFor(() => expect(screen.getAllByTestId('movement-row').length).toBeGreaterThan(0));
    const button = within(rowOf('Testovská')).getByRole('button', { name: 'Príchod' });
    expect(button).toBeDisabled();
  });
});
