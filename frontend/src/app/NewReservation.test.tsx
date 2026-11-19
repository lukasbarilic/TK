import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { NewReservationPage } from '../features/reservations';
import { samplePlan } from '../features/plan/testData';
import { connection } from '../shared/offline';
import { originState } from '../shared/navigation/origin';
import { platform } from '../shared/platform';
import { mockApi, receptionist } from '../shared/test/handlers';
import { renderWithProviders } from '../shared/test/render';
import { server } from '../shared/test/server';
import { NewReservationRoute } from './NewReservationRoute';
import { PlanRoute } from './PlanRoute';
import { ReservationsRoute } from './ReservationsRoute';

const clerk = {
  ...receptionist,
  permissions: [...(receptionist.permissions ?? []), { code: 'RESERVATION_EDIT' }],
};

type Body = Record<string, unknown> & { guests?: Record<string, unknown>[] };
let cardPosts: Body[] = [];
let stayPosts: Body[] = [];
let availableQueries: URL[] = [];

const problem = (status: number, code: string, extra: object = {}) =>
  HttpResponse.json({ status, code, title: code, ...extra }, { status });

const detail = (id: number) => ({ id, reservationNumber: `R-2026-0${id}`, status: 'RESERVED', arrivalDate: '2026-11-20', departureDate: '2026-11-22', version: 0, guests: [] });
const stay = { id: 900, reservationId: 501, roomId: 2, bedId: 21, dateFrom: '2026-11-20', dateTo: '2026-11-22' };

function mockServer(opts: { withStay?: () => Response } = {}) {
  cardPosts = [];
  stayPosts = [];
  availableQueries = [];
  mockApi(clerk);
  server.use(
    http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
    http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
    http.get('/api/accommodation-plan', () => HttpResponse.json(samplePlan())),
    http.get('/api/catalog/room-categories', () => HttpResponse.json([{ id: 2, code: 'K2', name: 'dvojlôžková' }, { id: 3, code: 'K1', name: 'jednolôžková' }])),
    http.get('/api/catalog/payers', () => HttpResponse.json([{ id: 7, code: 'SELF', name: 'Samoplatca Demo', type: 'SELF', active: true }])),
    http.get('/api/clients', ({ request }) => {
      const q = new URL(request.url).searchParams.get('q');
      return HttpResponse.json(q?.toLowerCase().startsWith('test') ? [{ id: 42, firstName: 'Adam', lastName: 'Testovský', birthYear: 1951, lastStayDate: '2026-11-10' }] : []);
    }),
    http.get('/api/rooms/available', ({ request }) => {
      availableQueries.push(new URL(request.url));
      return HttpResponse.json([
        { roomId: 2, roomNumber: '102', buildingId: 1, buildingName: 'ALF', receptionId: 2, categoryId: 2, categoryName: 'K2', matchesCategory: true,
          freeBeds: [{ id: 21, bedNumber: 1, type: 'REGULAR' }, { id: 22, bedNumber: 2, type: 'REGULAR' }] },
      ]);
    }),
    http.post('/api/reservations/with-stay', async ({ request }) => {
      stayPosts.push((await request.json()) as Body);
      return opts.withStay ? opts.withStay() : HttpResponse.json({ reservation: detail(501), stay }, { status: 201 });
    }),
    http.post('/api/reservations', async ({ request }) => {
      cardPosts.push((await request.json()) as Body);
      return HttpResponse.json(detail(502), { status: 201 });
    }),
    http.get('/api/reservations', () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0 })),
  );
}

function DetailStub() {
  const loc = useLocation();
  const origin = (loc.state as { origin?: { label: string; to: string } } | null)?.origin;
  return <div data-testid="detail-stub">{loc.pathname}|{origin?.to ?? ''}</div>;
}

const renderForm = (state?: object) =>
  renderWithProviders(
    <MemoryRouter initialEntries={[{ pathname: '/reservations/new', state: state ?? originState('Rezervačné karty', '/reservations') }]}>
      <Routes>
        <Route path="/reservations/new" element={<NewReservationPage me={clerk} />} />
        <Route path="/reservations/:id" element={<DetailStub />} />
        <Route path="/reservations" element={<div data-testid="list-stub" />} />
      </Routes>
    </MemoryRouter>,
  );

const renderApp = (path: string) =>
  renderWithProviders(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/plan" element={<PlanRoute />} />
        <Route path="/reservations" element={<ReservationsRoute />} />
        <Route path="/reservations/new" element={<NewReservationRoute />} />
        <Route path="/reservations/:id" element={<DetailStub />} />
      </Routes>
    </MemoryRouter>,
  );

const setDates = (from: string, to: string) => {
  fireEvent.change(screen.getByLabelText('Od (príchod)'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('Do (odchod)'), { target: { value: to } });
};
const fillStay = async (from = '2026-11-20', to = '2026-11-22') => {
  setDates(from, to);
  await screen.findByRole('option', { name: /K2/ });
  await userEvent.selectOptions(screen.getByLabelText('Kategória'), '2');
  await screen.findByRole('option', { name: 'Samoplatca Demo' });
  await userEvent.selectOptions(screen.getByLabelText('Platiteľ'), '7');
};
const addExistingClient = async () => {
  await userEvent.type(screen.getByLabelText('Vyhľadať existujúceho klienta'), 'Test');
  await userEvent.click(screen.getByRole('button', { name: 'Hľadať' }));
  const hit = await screen.findByTestId('client-hit');
  expect(hit).toHaveTextContent('Adam Testovský 1951');
  await userEvent.click(within(hit).getByRole('button', { name: 'Pridať' }));
};

beforeEach(async () => {
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('Nová rezervácia – formulár', () => {
  it('existujúci klient: vyhľadá cez /api/clients, dopočíta noci a uloží len kartu → detail novej karty', async () => {
    mockServer();
    renderForm();
    await addExistingClient();
    await fillStay();
    expect(screen.getByTestId('new-nights')).toHaveTextContent('2');
    expect(screen.getByTestId('summary-term')).toHaveTextContent('2026-11-20 – 2026-11-22');
    await userEvent.click(screen.getByTestId('save-card'));

    expect(await screen.findByTestId('detail-stub')).toHaveTextContent('/reservations/502|/reservations');
    expect(stayPosts).toHaveLength(0);
    expect(cardPosts).toHaveLength(1);
    expect(cardPosts[0]).toMatchObject({ arrivalDate: '2026-11-20', departureDate: '2026-11-22', bookedCategoryId: 2, payerId: 7, adults: 1, children: 0 });
    expect(cardPosts[0].guests).toEqual([expect.objectContaining({ clientId: 42, firstName: 'Adam', lastName: 'Testovský' })]);
  });

  it('nový klient: vyplnené meno a priezvisko sa pošlo bez clientId; hlavný hosť ide prvý', async () => {
    mockServer();
    renderForm();
    await addExistingClient();
    await userEvent.click(screen.getByRole('button', { name: '+ Nový klient (nie je v zozname)' }));
    const rows = screen.getAllByTestId('guest-form-row');
    await userEvent.type(within(rows[1]).getByLabelText('Meno'), 'Zuzana');
    await userEvent.type(within(rows[1]).getByLabelText('Priezvisko'), 'Testovacia');
    await userEvent.click(within(rows[1]).getByRole('radio', { name: 'Hlavný hosť' }));
    await fillStay();
    await userEvent.click(screen.getByTestId('save-card'));

    await screen.findByTestId('detail-stub');
    const guests = cardPosts[0].guests as Record<string, unknown>[];
    expect(guests[0]).toMatchObject({ firstName: 'Zuzana', lastName: 'Testovacia' });
    expect(guests[0].clientId).toBeUndefined();
    expect(guests[1]).toMatchObject({ clientId: 42 });
  });

  it('súhrn ukáže počet voľných lôžok kategórie z /api/rooms/available', async () => {
    mockServer();
    renderForm();
    await fillStay();
    expect(await screen.findByTestId('summary-free')).toHaveTextContent('V termíne sú voľné 2 lôžka kategórie.');
    const q = availableQueries.at(-1)?.searchParams;
    expect(q?.get('categoryId')).toBe('2');
    expect(q?.get('from')).toBe('2026-11-20');
    expect(q?.get('to')).toBe('2026-11-22');
  });

  it('validácia dátumov: odchod pred príchodom nepustí uloženie', async () => {
    mockServer();
    renderForm();
    await addExistingClient();
    await fillStay('2026-11-22', '2026-11-20');
    await userEvent.click(screen.getByTestId('save-card'));
    expect(await screen.findByText('Odchod musí byť neskôr ako príchod.')).toBeInTheDocument();
    expect(screen.getByTestId('new-nights')).toHaveTextContent('0');
    expect(cardPosts).toHaveLength(0);
  });

  it('validácia: bez hosťa, kategórie a platiteľa sa nič neodošle', async () => {
    mockServer();
    renderForm();
    await userEvent.click(screen.getByTestId('save-card'));
    expect(await screen.findByTestId('guests-error')).toHaveTextContent('Pridajte aspoň jedného hosťa.');
    expect(screen.getAllByText('Povinný údaj.').length).toBeGreaterThan(0);
    expect(cardPosts).toHaveLength(0);
  });

  it('chyby z fieldViolations sa ukážu pri poli', async () => {
    mockServer();
    server.use(http.post('/api/reservations', () => problem(400, 'VALIDATION_FAILED', { fieldViolations: [{ field: 'departureDate', message: 'Odchod je mimo prevádzky.' }] })));
    renderForm();
    await addExistingClient();
    await fillStay();
    await userEvent.click(screen.getByTestId('save-card'));
    expect(await screen.findByText('Odchod je mimo prevádzky.')).toBeInTheDocument();
    expect(screen.queryByTestId('detail-stub')).toBeNull();
  });

  it('odchod z rozpísaného formulára sa pýta na potvrdenie', async () => {
    mockServer();
    renderForm();
    await userEvent.type(screen.getByLabelText('Vyhľadať existujúceho klienta'), 'x'); // vyhľadávanie nie je súčasť karty
    await userEvent.click(screen.getByRole('button', { name: '+ Nový klient (nie je v zozname)' }));
    await userEvent.type(screen.getByLabelText('Meno'), 'Zuzana');
    await userEvent.click(screen.getByTestId('new-cancel'));
    expect(await screen.findByText('Zahodiť rozpísanú rezerváciu?')).toBeInTheDocument();
    await userEvent.click(screen.getByTestId('leave-stay'));
    await waitFor(() => expect(screen.queryByTestId('leave-discard')).toBeNull());
    expect(screen.queryByTestId('list-stub')).toBeNull();
    await userEvent.click(screen.getByTestId('new-cancel'));
    await userEvent.click(await screen.findByTestId('leave-discard'));
    expect(await screen.findByTestId('list-stub')).toBeInTheDocument();
  });

  it('nerozpísaný formulár sa zatvorí bez otázky', async () => {
    mockServer();
    renderForm();
    await userEvent.click(screen.getByTestId('new-cancel'));
    expect(await screen.findByTestId('list-stub')).toBeInTheDocument();
  });

  it('upozornenie 409 confirmable sa potvrdí a „s lôžkom“ sa pošle znova s confirm', async () => {
    let n = 0;
    mockServer({
      withStay: () => (++n === 1 ? problem(409, 'CONFIRMATION_REQUIRED', { confirmable: true, warnings: ['RELEASES_HELD_BED'] }) : HttpResponse.json({ reservation: detail(501), stay }, { status: 201 })),
    });
    renderForm({ ...originState('Plán ubytovania', '/plan'), prefill: { bedId: 21, roomId: 2, roomLabel: 'ALF 102', bedLabel: '1', day: '2026-11-20', categoryId: 2 } });
    await addExistingClient();
    await screen.findByRole('option', { name: 'Samoplatca Demo' });
  await userEvent.selectOptions(screen.getByLabelText('Platiteľ'), '7');
    await userEvent.click(screen.getByTestId('save-with-stay'));
    expect(await screen.findByTestId('confirm-warnings')).toHaveTextContent('Ubytovaním sa uvoľní lôžko držané pre iný pobyt.');
    await userEvent.click(screen.getByRole('button', { name: 'Pokračovať' }));
    expect(await screen.findByTestId('detail-stub')).toHaveTextContent('|/plan');
    expect(stayPosts.map((b) => b.confirm)).toEqual([undefined, ['RELEASES_HELD_BED']]);
  });

  it('bez spojenia sú tlačidlá uloženia neaktívne', async () => {
    mockServer();
    connection.reset('offline');
    renderForm();
    expect(await screen.findByTestId('new-reservation-offline')).toBeInTheDocument();
    expect(screen.getByTestId('save-card')).toBeDisabled();
  });
});

describe('Nová rezervácia z plánu', () => {
  const openFromPlan = async () => {
    mockServer();
    renderApp('/plan');
    await screen.findAllByTestId('plan-bar');
    const row = document.querySelector('[data-bed-id="21"]') as HTMLElement;
    // 20. 11. = stĺpec 3 (okno od 17. 11., deň 36 px)
    fireEvent.contextMenu(within(row).getByTestId('plan-bed-cells'), { clientX: 3 * 36 + 5, clientY: 10 });
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Nová rezervácia sem…' }));
  };

  it('pravé tlačidlo na voľnom lôžku otvorí formulár s lôžkom a dátumom; uloží s lôžkom a Späť vedie do plánu', async () => {
    await openFromPlan();
    expect(await screen.findByTestId('new-reservation')).toBeInTheDocument();
    expect(screen.getByTestId('new-reservation-from-plan')).toHaveTextContent('ALF 102, Lôžko 1');
    expect(screen.getByLabelText('Od (príchod)')).toHaveValue('2026-11-20');
    expect(screen.getByTestId('summary-bed')).toHaveTextContent('ALF 102, Lôžko 1');
    // kategória izby sa predvyplní z plánu
    await waitFor(() => expect(screen.getByLabelText('Kategória')).toHaveValue('2'));

    await addExistingClient();
    await screen.findByRole('option', { name: 'Samoplatca Demo' });
  await userEvent.selectOptions(screen.getByLabelText('Platiteľ'), '7');
    fireEvent.change(screen.getByLabelText('Do (odchod)'), { target: { value: '2026-11-23' } });
    await userEvent.click(screen.getByTestId('save-with-stay'));

    expect(await screen.findByTestId('detail-stub')).toHaveTextContent('/reservations/501|/plan');
    expect(cardPosts).toHaveLength(0);
    expect(stayPosts).toHaveLength(1);
    expect(stayPosts[0]).toMatchObject({ bedId: 21, arrivalDate: '2026-11-20', departureDate: '2026-11-23', bookedCategoryId: 2, payerId: 7, extraBed: false });
  });

  it('obsadené lôžko (409 BED_OCCUPIED): zobrazí chybu, nezaloží kartu a zostane vo formulári', async () => {
    mockServer({ withStay: () => problem(409, 'BED_OCCUPIED') });
    renderApp('/plan');
    await screen.findAllByTestId('plan-bar');
    const row = document.querySelector('[data-bed-id="21"]') as HTMLElement;
    fireEvent.contextMenu(within(row).getByTestId('plan-bed-cells'), { clientX: 3 * 36 + 5, clientY: 10 });
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Nová rezervácia sem…' }));
    await addExistingClient();
    await screen.findByRole('option', { name: 'Samoplatca Demo' });
  await userEvent.selectOptions(screen.getByLabelText('Platiteľ'), '7');
    await userEvent.click(screen.getByTestId('save-with-stay'));

    expect(await screen.findByTestId('new-reservation-error')).toHaveTextContent('Lôžko je v tomto termíne už obsadené.');
    expect(cardPosts).toHaveLength(0);
    expect(screen.queryByTestId('detail-stub')).toBeNull();
    expect(screen.getByTestId('new-reservation')).toBeInTheDocument();
  });

  it('pravé tlačidlo na obsadenom dni menu neotvorí', async () => {
    mockServer();
    renderApp('/plan');
    await screen.findAllByTestId('plan-bar');
    const row = document.querySelector('[data-bed-id="11"]') as HTMLElement;
    fireEvent.contextMenu(within(row).getByTestId('plan-bed-cells'), { clientX: 3 * 36 + 5, clientY: 10 }); // 20. 11. – obsadené pobytom
    expect(screen.queryByRole('menuitem', { name: 'Nová rezervácia sem…' })).toBeNull();
  });

  it('tlačidlo „+ Nová rezervácia“ v pláne otvorí prázdny formulár bez lôžka', async () => {
    mockServer();
    renderApp('/plan');
    await userEvent.click(await screen.findByTestId('plan-new-reservation'));
    expect(await screen.findByTestId('new-reservation')).toBeInTheDocument();
    expect(screen.queryByTestId('new-reservation-from-plan')).toBeNull();
    expect(screen.getByTestId('summary-bed')).toHaveTextContent('Zatiaľ neprideľovať');
  });
});

describe('Zoznam kariet', () => {
  it('tlačidlo „+ Nová rezervácia“ je len s RESERVATION_EDIT a len online', async () => {
    mockServer();
    renderApp('/reservations');
    expect(await screen.findByTestId('reservations-new')).toBeInTheDocument();
  });

  it('bez oprávnenia RESERVATION_EDIT tlačidlo nie je', async () => {
    mockServer();
    mockApi(receptionist);
    renderApp('/reservations');
    await screen.findByTestId('reservations-page');
    await waitFor(() => expect(screen.queryByTestId('reservations-new')).toBeNull());
  });

  it('bez spojenia tlačidlo nie je', async () => {
    mockServer();
    connection.reset('offline');
    renderApp('/reservations');
    await screen.findByTestId('reservations-page');
    expect(screen.queryByTestId('reservations-new')).toBeNull();
  });
});
