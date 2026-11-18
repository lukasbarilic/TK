import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
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
const renderDetail = (canSeeContacts = true) =>
  renderWithProviders(
    <MemoryRouter>
      <ReservationDetailPage id={912} canSeeContacts={canSeeContacts} />
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
    await user.click(screen.getByRole('button', { name: '›' }));
    await waitFor(() => expect(seen.some((u) => u.searchParams.get('page') === '1')).toBe(true));
    expect(screen.queryByTestId('reservations-local-warning')).not.toBeInTheDocument();
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

  it('úprava posiela PATCH s version; offline je tlačidlo neaktívne', async () => {
    mockOnline();
    let body: unknown;
    server.use(
      http.patch('/api/reservations/912', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...card, version: 4, departureDate: '2026-11-23' });
      }),
    );
    renderDetail();
    const user = userEvent.setup();
    const dep = await screen.findByLabelText('Odchod');
    await user.clear(dep);
    await user.type(dep, '2026-11-23');
    await user.click(screen.getByRole('button', { name: 'Uložiť' }));
    await waitFor(() => expect(body).toMatchObject({ version: 3, departureDate: '2026-11-23' }));
    expect(await screen.findByText('Karta bola uložená.')).toBeInTheDocument();
  });

  it('konflikt verzie (409) ukáže upozornenie', async () => {
    mockOnline();
    server.use(http.patch('/api/reservations/912', () => HttpResponse.json({ status: 409, code: 'VERSION_CONFLICT' }, { status: 409 })));
    renderDetail();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Uložiť' }));
    expect(await screen.findByTestId('version-conflict')).toBeInTheDocument();
  });

  it('offline je úprava neaktívna', async () => {
    await platform.vault.put(DS.reservation, '912', card, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderDetail();
    expect(await screen.findByRole('button', { name: 'Uložiť' })).toBeDisabled();
  });
});
