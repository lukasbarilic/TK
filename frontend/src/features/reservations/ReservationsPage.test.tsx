import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import type { components } from '../../shared/api/schema';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { ReservationDetailPage } from './ReservationDetailPage';
import { ReservationsPage } from './ReservationsPage';
import { normalize } from './reservationsApi';

const card = {
  id: 912, reservationNumber: 'R-2026-0912', status: 'RESERVED' as const,
  arrivalDate: '2026-11-19', departureDate: '2026-11-22', version: 3,
  guests: [{ id: 1, firstName: 'Žofia', lastName: 'Ľudová', primaryGuest: true, email: 'zofia@example.invalid', phone: '+000 000 000' }],
};

const renderList = () =>
  renderWithProviders(
    <MemoryRouter initialEntries={['/reservations']}>
      <Routes><Route path="/reservations" element={<ReservationsPage />} /></Routes>
    </MemoryRouter>,
  );
type Me = components['schemas']['MeView'];
const editor: Me = { ...receptionist, permissions: [...(receptionist.permissions ?? []), { code: 'RESERVATION_EDIT', scopes: [{ receptionId: 2 }] }] };

const renderDetail = (canSeeContacts = true, me: Me = editor) =>
  renderWithProviders(
    <MemoryRouter>
      <ReservationDetailPage id={912} canSeeContacts={canSeeContacts} me={me} />
    </MemoryRouter>,
  );

beforeEach(async () => {
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('normalize', () => {
  it('odstráni diakritiku a veľkosť písmen', () => expect(normalize('  ŽOFIA Ľudová ')).toBe('zofia ludova'));
});

describe('Rezervačné karty – online', () => {
  it('vyhľadá cez GET /api/reservations s parametrami a stránkuje', async () => {
    const seen: URL[] = [];
    server.use(
      http.get('/api/reservations', ({ request }) => {
        const url = new URL(request.url);
        seen.push(url);
        return HttpResponse.json({
          content: [{ id: 912, reservationNumber: 'R-2026-0912', guestLastName: 'Ľudová', guestFirstName: 'Žofia', arrivalDate: '2026-11-19', departureDate: '2026-11-22', status: 'RESERVED' }],
          page: Number(url.searchParams.get('page')), size: 20, totalElements: 45,
        });
      }),
    );
    renderList();
    const user = userEvent.setup();
    expect(await screen.findAllByTestId('reservation-row')).toHaveLength(1);
    await user.type(screen.getByLabelText('Meno alebo číslo karty'), 'Ľudová');
    await user.click(screen.getByRole('button', { name: 'Hľadať' }));
    await waitFor(() => expect(seen.some((u) => u.searchParams.get('q') === 'Ľudová')).toBe(true));
    expect(screen.getByTestId('reservations-page-info')).toHaveTextContent('Strana 1 z 3');
    await user.click(screen.getByRole('button', { name: 'Ďalšia strana' }));
    await waitFor(() => expect(seen.some((u) => u.searchParams.get('page') === '1')).toBe(true));
    expect(screen.queryByTestId('reservations-local-warning')).not.toBeInTheDocument();
  });

  const listWith = (seen: URL[]) =>
    server.use(
      http.get('/api/reservations', ({ request }) => {
        seen.push(new URL(request.url));
        return HttpResponse.json({
          content: [{ id: 912, reservationNumber: 'R-2026-0912', guestLastName: 'Ľudová', guestFirstName: 'Žofia', guestBirthYear: 1970, arrivalDate: '2026-11-19', departureDate: '2026-11-22', status: 'RESERVED', bookedCategoryName: 'Dvojlôžková' }],
          page: 0, size: 20, totalElements: 1,
        });
      }),
    );

  it('hosť je prvý stĺpec, termín je čitateľný s počtom nocí a riadok vedie na detail', async () => {
    listWith([]);
    renderList();
    const row = await screen.findByTestId('reservation-row');
    const headers = within(screen.getByTestId('reservations-table')).getAllByRole('columnheader').map((h) => h.textContent);
    expect(headers).toEqual(['Hosť', 'Termín', 'Kategória', 'Číslo karty', 'Stav']);
    const cells = within(row).getAllByRole('cell');
    expect(cells[0]).toHaveTextContent('Ľudová Žofia');
    expect(cells[0]).toHaveTextContent('nar. 1970');
    expect(cells[1]).toHaveTextContent('19. 11. 2026 – 22. 11. 2026');
    expect(cells[1]).toHaveTextContent('3 noci');
    expect(within(row).getByRole('link', { name: 'Ľudová Žofia' })).toHaveAttribute('href', '/reservations/912');
    expect(screen.getByTestId('reservations-count')).toHaveTextContent('1 karta');
  });

  it('filter obdobia pošle from a to (do vrátane → server dostane nasledujúci deň); zrušenie filtrov ich vymaže', async () => {
    const seen: URL[] = [];
    listWith(seen);
    renderList();
    const user = userEvent.setup();
    await screen.findByTestId('reservation-row');
    await user.type(screen.getByLabelText('Pobyt od'), '2026-11-20');
    await user.type(screen.getByLabelText('Pobyt do'), '2026-11-30');
    await waitFor(() =>
      expect(seen.some((u) => u.searchParams.get('from') === '2026-11-20' && u.searchParams.get('to') === '2026-12-01')).toBe(true),
    );
    await user.click(screen.getByTestId('reservations-clear'));
    expect(screen.getByLabelText('Pobyt od')).toHaveValue('');
    await waitFor(() => expect(seen.at(-1)?.searchParams.has('from')).toBe(false));
  });

  it('obdobie s „do“ pred „od“ ukáže chybu a nevyhľadáva', async () => {
    const seen: URL[] = [];
    listWith(seen);
    renderList();
    const user = userEvent.setup();
    await screen.findByTestId('reservation-row');
    await user.type(screen.getByLabelText('Pobyt od'), '2026-11-20');
    const count = seen.length;
    await user.type(screen.getByLabelText('Pobyt do'), '2026-11-10');
    expect(await screen.findByText('Dátum „do“ je pred dátumom „od“')).toBeInTheDocument();
    expect(seen.filter((u) => u.searchParams.get('to') === '2026-11-11')).toHaveLength(0);
    expect(seen.length).toBeGreaterThanOrEqual(count);
  });
});

describe('Rezervačné karty – offline', () => {
  it('lokálne hľadanie bez diakritiky a veľkosti písmen, podľa čísla; upozornenie', async () => {
    await platform.vault.put(DS.reservation, '912', card, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderList();
    const user = userEvent.setup();
    expect(await screen.findByTestId('reservations-local-warning')).toHaveTextContent('len v kartách uložených na tomto PC');
    await user.type(screen.getByLabelText('Meno alebo číslo karty'), 'zofia LUDOVA');
    await user.click(screen.getByRole('button', { name: 'Hľadať' }));
    await waitFor(() => expect(within(screen.getByTestId('reservation-row')).getByText('Ľudová Žofia')).toBeInTheDocument());
    await user.clear(screen.getByLabelText('Meno alebo číslo karty'));
    await user.type(screen.getByLabelText('Meno alebo číslo karty'), '0912');
    await user.click(screen.getByRole('button', { name: 'Hľadať' }));
    await waitFor(() => expect(screen.getAllByTestId('reservation-row')).toHaveLength(1));
    await user.clear(screen.getByLabelText('Meno alebo číslo karty'));
    await user.type(screen.getByLabelText('Meno alebo číslo karty'), 'Neznámy');
    await user.click(screen.getByRole('button', { name: 'Hľadať' }));
    expect(await screen.findByTestId('reservations-empty')).toBeInTheDocument();
  });

  it('lokálne hľadanie berie do úvahy aj obdobie pobytu', async () => {
    await platform.vault.put(DS.reservation, '912', card, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderList();
    const user = userEvent.setup();
    expect(await screen.findAllByTestId('reservation-row')).toHaveLength(1);
    // karta 19. – 22. 11.: deň odchodu sa už nepočíta
    await user.type(screen.getByLabelText('Pobyt od'), '2026-11-22');
    expect(await screen.findByTestId('reservations-empty')).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Pobyt od'));
    await user.type(screen.getByLabelText('Pobyt od'), '2026-11-21');
    await user.type(screen.getByLabelText('Pobyt do'), '2026-11-21');
    expect(await screen.findAllByTestId('reservation-row')).toHaveLength(1);
  });
});

describe('Detail karty', () => {
  const mockOnline = () =>
    server.use(
      http.get('/api/reservations/912', () => HttpResponse.json(card)),
      http.get('/api/reservations/912/stays', () =>
        HttpResponse.json([{ id: 5, reservationId: 912, buildingName: 'A', roomNumber: '101', roomId: 7, bedNumber: 1, dateFrom: '2026-11-19', dateTo: '2026-11-22' }]),
      ),
    );

  it('online: hostia, kontakty, pobyty s odkazom na plán', async () => {
    mockOnline();
    renderDetail();
    expect(await screen.findByTestId('reservation-number')).toHaveTextContent('R-2026-0912');
    expect(screen.getByTestId('guest-contact')).toHaveTextContent('zofia@example.invalid');
    const link = await screen.findByRole('link', { name: 'Zobraziť v pláne' });
    expect(link).toHaveAttribute('href', '/plan?roomId=7&date=2026-11-19');
  });

  it('hlavička: meno hlavného hosťa, „+N“ a číslo karty; bočný panel s termínom, nocami, ubytovaním a stavom', async () => {
    server.use(
      http.get('/api/reservations/912', () =>
        HttpResponse.json({
          ...card,
          status: 'CHECKED_IN',
          guests: [
            { id: 2, firstName: 'Ján', lastName: 'Druhý', primaryGuest: false },
            { id: 1, firstName: 'Žofia', lastName: 'Ľudová', primaryGuest: true },
          ],
        }),
      ),
      http.get('/api/reservations/912/stays', () =>
        HttpResponse.json([{ id: 5, reservationId: 912, buildingName: 'A', roomNumber: '101', roomId: 7, bedNumber: 1, dateFrom: '2026-11-19', dateTo: '2026-11-22' }]),
      ),
    );
    renderDetail();
    await screen.findByTestId('reservation-title');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Ľudová Žofia +1');
    expect(screen.getByTestId('reservation-number')).toHaveTextContent('R-2026-0912');
    const panel = screen.getByTestId('reservation-side-panel');
    expect(within(panel).getByText('Hlavný hosť')).toBeInTheDocument();
    expect(within(panel).getAllByTestId('guest-row')).toHaveLength(2);
    expect(within(panel).getByTestId('reservation-dates')).toHaveTextContent('2026-11-19 – 2026-11-22');
    expect(within(panel).getByTestId('reservation-nights')).toHaveTextContent('3 noci');
    expect(await within(panel).findByTestId('stay-row')).toHaveTextContent('A 101');
    expect(within(panel).getAllByTestId('status-step').find((el) => el.getAttribute('aria-current') === 'step')).toHaveTextContent('2. Prítomný');
  });

  it('karta bez hostí: číslo karty ako nadpis a „Bez hostí“ v paneli', async () => {
    server.use(
      http.get('/api/reservations/912', () => HttpResponse.json({ ...card, guests: [] })),
      http.get('/api/reservations/912/stays', () => HttpResponse.json([])),
    );
    renderDetail();
    await screen.findByTestId('reservation-number');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('R-2026-0912');
    expect(screen.getByText('Bez hostí')).toBeInTheDocument();
  });

  it('bez RESERVATION_VIEW sa kontakty nezobrazia', async () => {
    mockOnline();
    renderDetail(false);
    await screen.findByTestId('guest-row');
    expect(screen.queryByTestId('guest-contact')).not.toBeInTheDocument();
  });

  it('offline: detail z trezora; karta mimo trezora → prázdny stav', async () => {
    await platform.vault.put(DS.reservation, '912', card, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    const first = renderDetail();
    expect(await screen.findByTestId('reservation-number')).toHaveTextContent('R-2026-0912');
    expect(screen.getByTestId('data-age')).toBeInTheDocument();
    first.unmount();
    await platform.vault.remove(DS.reservation, ['912']);
    renderDetail();
    expect(await screen.findByTestId('reservation-not-stored')).toBeInTheDocument();
  });

  const openEdit = async (user = userEvent.setup()) => {
    await user.click(await screen.findByRole('button', { name: 'Upraviť termín a príznaky karty' }));
    return within(await screen.findByTestId('reservation-edit'));
  };

  it('úprava termínu sa otvorí z bočného panelu v dialógu a pošle PATCH s version', async () => {
    mockOnline();
    let body: unknown;
    server.use(
      http.patch('/api/reservations/912', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...card, version: 4, departureDate: '2026-11-23' });
      }),
    );
    renderDetail();
    expect(screen.queryByTestId('reservation-edit')).toBeNull();
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    const dep = dialog.getByLabelText('Odchod');
    await user.clear(dep);
    await user.type(dep, '2026-11-23');
    await user.click(dialog.getByRole('button', { name: 'Uložiť' }));
    await waitFor(() => expect(body).toMatchObject({ version: 3, departureDate: '2026-11-23' }));
    await waitFor(() => expect(screen.queryByTestId('reservation-edit')).toBeNull());
  });

  it('výhradné užívanie izby: dá sa zmeniť v dialógu (PATCH)', async () => {
    mockOnline();
    let body: unknown;
    server.use(
      http.patch('/api/reservations/912', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...card, version: 4, exclusiveRoomUse: true });
      }),
    );
    renderDetail();
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    const box = dialog.getByRole('checkbox', { name: 'Výhradné užívanie izby' });
    expect(box).not.toBeChecked();
    expect(screen.queryByTestId('exclusive-badge')).toBeNull();
    await user.click(box);
    await user.click(dialog.getByRole('button', { name: 'Uložiť' }));
    await waitFor(() => expect(body).toMatchObject({ version: 3, exclusiveRoomUse: true }));
  });

  it('karta s výhradným užívaním a „Nepresúvať“ má štítky a zaškrtnuté políčka v dialógu', async () => {
    mockOnline();
    server.use(http.get('/api/reservations/912', () => HttpResponse.json({ ...card, exclusiveRoomUse: true, doNotMove: true })));
    renderDetail();
    expect(await screen.findByTestId('exclusive-badge')).toHaveTextContent('Výhradné užívanie izby');
    expect(screen.getByTestId('do-not-move-badge')).toHaveTextContent('Nepresúvať');
    const dialog = await openEdit();
    expect(dialog.getByRole('checkbox', { name: 'Výhradné užívanie izby' })).toBeChecked();
    expect(dialog.getByRole('checkbox', { name: 'Nepresúvať' })).toBeChecked();
  });

  it('konflikt verzie (409) ukáže upozornenie v dialógu', async () => {
    mockOnline();
    server.use(http.patch('/api/reservations/912', () => HttpResponse.json({ status: 409, code: 'VERSION_CONFLICT' }, { status: 409 })));
    renderDetail();
    const user = userEvent.setup();
    const dialog = await openEdit(user);
    await user.click(dialog.getByRole('button', { name: 'Uložiť' }));
    expect(await screen.findByTestId('version-conflict')).toBeInTheDocument();
  });

  it('bez RESERVATION_EDIT sa úprava karty neponúkne', async () => {
    mockOnline();
    renderDetail(true, receptionist);
    expect(await screen.findByTestId('reservation-dates')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Upraviť termín a príznaky karty' })).toBeNull();
  });

  it('offline je úprava neaktívna', async () => {
    await platform.vault.put(DS.reservation, '912', card, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderDetail();
    expect(await screen.findByRole('button', { name: 'Upraviť termín a príznaky karty' })).toBeDisabled();
  });
});
