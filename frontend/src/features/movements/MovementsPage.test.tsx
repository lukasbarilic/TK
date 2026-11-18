import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { connection } from '../../shared/offline';
import { DS, movementsKey } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { MovementsPage } from './MovementsPage';
import { sortMovements, type Movements } from './movementsApi';

const mv = (o: object) => ({ reservationNumber: 'R-2026-0001', guestCount: 1, arrivalDate: '2026-11-18', departureDate: '2026-11-22', status: 'RESERVED', ...o });
const TODAY: Movements = {
  receptionId: 2, date: '2026-11-18',
  arrivals: [
    mv({ reservationId: 1, guestName: 'Testovacia Z.', roomNumber: 'B 204' }),
    mv({ reservationId: 2, guestName: 'Ukážková M.', roomNumber: 'A 205', payer: { id: 1, name: 'Platiteľ Demo' } }),
    mv({ reservationId: 3, guestName: 'Bez Izby', status: 'NO_ROOM' }),
  ],
  departures: [mv({ reservationId: 4, guestName: 'Fiktívny L.', roomNumber: 'A 108', status: 'CHECKED_IN' })],
};
const TOMORROW: Movements = { receptionId: 2, date: '2026-11-19', arrivals: [], departures: [] };

function mockServer() {
  server.use(
    http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, code: 'R2', name: 'Recepcia B', active: true }])),
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: '2026-11-18' })),
    http.get('/api/receptions/:id/movements', ({ request }) =>
      HttpResponse.json(new URL(request.url).searchParams.get('date') === '2026-11-18' ? TODAY : TOMORROW),
    ),
  );
}

const renderPage = () =>
  renderWithProviders(
    <MemoryRouter initialEntries={['/movements']}>
      <Routes>
        <Route path="/movements" element={<MovementsPage me={receptionist} />} />
        <Route path="/reservations/:id" element={<div data-testid="card-detail" />} />
      </Routes>
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

describe('sortMovements', () => {
  it('bez izby hore, potom izba a meno', () => {
    const r = sortMovements([
      { guestName: 'B', roomNumber: 'A 10' }, { guestName: 'Z' }, { guestName: 'A', roomNumber: 'A 9' }, { guestName: 'A', roomNumber: 'A 10' },
    ]);
    expect(r.map((x) => `${x.roomNumber ?? '-'}:${x.guestName}`)).toEqual(['-:Z', 'A 9:A', 'A 10:A', 'A 10:B']);
  });
});

describe('Príchody a odchody', () => {
  it('online: dva zoznamy, počty, triedenie podľa izby a klik na riadok otvorí kartu', async () => {
    mockServer();
    renderPage();
    const arrivals = await screen.findByTestId('movements-arrivals');
    expect(within(arrivals).getByTestId('movements-arrivals-count')).toHaveTextContent('3');
    expect(screen.getByTestId('movements-departures-count')).toHaveTextContent('1');
    const rows = within(arrivals).getAllByTestId('movement-row');
    expect(rows[0]).toHaveTextContent('bez izby');
    expect(rows[1]).toHaveTextContent('A 205');
    expect(rows[2]).toHaveTextContent('B 204');
    expect(rows[1]).toHaveTextContent('Platiteľ Demo');
    expect(screen.queryByTestId('data-age')).not.toBeInTheDocument();
    await userEvent.setup().click(rows[1]);
    expect(await screen.findByTestId('card-detail')).toBeInTheDocument();
  });

  it('prepnutie na zajtra načíta nasledujúci obchodný deň; prázdny zoznam', async () => {
    mockServer();
    const dates: string[] = [];
    server.use(
      http.get('/api/receptions/:id/movements', ({ request }) => {
        const d = new URL(request.url).searchParams.get('date') as string;
        dates.push(d);
        return HttpResponse.json(d === '2026-11-18' ? TODAY : TOMORROW);
      }),
    );
    renderPage();
    await screen.findByTestId('movements-arrivals');
    await userEvent.setup().click(screen.getByRole('button', { name: /^Zajtra/ }));
    expect(await screen.findByTestId('movements-arrivals-empty')).toBeInTheDocument();
    expect(screen.getByTestId('movements-departures-empty')).toBeInTheDocument();
    expect(screen.getByTestId('movements-arrivals-count')).toHaveTextContent('0');
    expect(dates).toEqual(['2026-11-18', '2026-11-19']);
    expect(screen.getByTestId('movements-date')).toHaveTextContent('2026-11-19');
  });

  it('offline: dáta z trezora s vekom dát', async () => {
    await platform.vault.put(DS.catalog, 'receptions', [{ id: 2, code: 'R2', name: 'Recepcia B', active: true }], { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: '2026-11-18' }, { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.movements, movementsKey(2, '2026-11-18'), TODAY, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderPage();
    expect(await screen.findByTestId('movements-arrivals')).toBeInTheDocument();
    expect(screen.getAllByTestId('movement-row')).toHaveLength(4);
    expect(screen.getByTestId('data-age')).toHaveTextContent('Údaje z');
  });

  it('offline bez uloženého dňa ukáže, že údaje nie sú uložené', async () => {
    await platform.vault.put(DS.catalog, 'receptions', [{ id: 2, code: 'R2', name: 'Recepcia B', active: true }], { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: '2026-11-18' }, { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.movements, movementsKey(2, '2026-11-18'), TODAY, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderPage();
    await screen.findByTestId('movements-arrivals');
    await userEvent.setup().click(screen.getByRole('button', { name: /^Zajtra/ }));
    expect(await screen.findByTestId('movements-not-stored')).toHaveTextContent('nie sú na tomto počítači uložené');
  });

  it('tlačidlo Tlačiť volá window.print', async () => {
    mockServer();
    const print = vi.spyOn(window, 'print').mockImplementation(() => {});
    renderPage();
    await screen.findByTestId('movements-arrivals');
    const btn = screen.getByRole('button', { name: 'Tlačiť' });
    await waitFor(() => expect(btn).toBeEnabled());
    await userEvent.setup().click(btn);
    expect(print).toHaveBeenCalledOnce();
    print.mockRestore();
  });
});
