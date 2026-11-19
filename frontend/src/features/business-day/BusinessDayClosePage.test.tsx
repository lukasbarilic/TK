import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { connection, syncEngine } from '../../shared/offline';
import { platform } from '../../shared/platform';
import { demoReceptions, receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { BusinessDayClosePage } from './BusinessDayClosePage';

const FORECAST = [83, 85, 88, 86, 79, 72, 75, 80, 84, 87, 90, 86, 74, 70].map((percent, i) => ({
  date: new Date(Date.UTC(2026, 10, 18 + i)).toISOString().slice(0, 10), percent,
}));
const REPORT = {
  businessDate: '2026-11-18',
  arrivals: { expectedPersons: 5, arrivedPersons: 4, noShowPersons: 1 },
  departures: { expectedPersons: 4, departedPersons: 4 },
  inHouse: { startOfDay: 112, endOfDay: 112 },
  occupancy: {
    complex: { beds: 144, occupied: 112, blocked: 6, free: 26, percent: 81.2 },
    buildings: [
      { buildingId: 1, name: 'Alfa', stats: { beds: 72, occupied: 58, blocked: 0, free: 14, percent: 80.6 } },
      { buildingId: 2, name: 'Beta', stats: { beds: 72, occupied: 54, blocked: 6, free: 12, percent: 81.8 } },
    ],
  },
  forecast: FORECAST,
};
const NOSHOW = { ruleCode: 'NO_SHOW', severity: 'INFO', reservationId: 9, message: 'Karta R-2026-0148 nedorazila (no-show).' };
const BLOCKING = {
  receptionId: 2, businessDate: '2026-11-18', canClose: false,
  blocking: [{ ruleCode: 'NO_ROOM', severity: 'BLOCKING', reservationId: 7, message: 'Ubytovaný hosť bez pridelenej izby' }],
  info: [{ ruleCode: 'NO_DOCTOR', severity: 'INFO', reservationId: 8, message: 'Hosť bez ošetrujúceho lekára' }],
  report: REPORT,
};
const CLEAN = { receptionId: 2, businessDate: '2026-11-18', canClose: true, blocking: [], info: [...BLOCKING.info, NOSHOW], report: REPORT };

function mock(preview: object, close?: () => Response) {
  server.use(
    http.get('/api/catalog/receptions', () => HttpResponse.json(demoReceptions)),
    http.post('/api/receptions/:id/business-day/close/preview', () => HttpResponse.json(preview)),
    http.post('/api/receptions/:id/business-day/close', () =>
      close ? close() : HttpResponse.json({ receptionId: 2, businessDate: '2026-11-19', status: 'OPEN' }),
    ),
  );
}

const renderPage = () =>
  renderWithProviders(
    <MemoryRouter initialEntries={['/business-day']}>
      <Routes>
        <Route path="/business-day" element={<BusinessDayClosePage me={receptionist} />} />
      </Routes>
    </MemoryRouter>,
  );

beforeEach(async () => {
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  vi.restoreAllMocks();
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

const ARCHIVE = [{ businessDate: '2026-11-17', closedAt: '2026-11-18T00:10:00Z', closedBy: 1 }];

function mockArchive() {
  server.use(
    http.get('/api/receptions/:id/business-day/reports', () => HttpResponse.json(ARCHIVE)),
    http.get('/api/receptions/:id/business-day/reports/:date', () =>
      HttpResponse.json({ ...ARCHIVE[0], report: { ...REPORT, businessDate: '2026-11-17' } }),
    ),
  );
}

describe('BusinessDayClosePage', () => {
  it('report dňa: dlaždice, tabuľka budov, graf výhľadu a kontrola pred uzavretím', async () => {
    mock(CLEAN);
    renderPage();
    const arrivals = await screen.findByTestId('tile-arrivals');
    expect(arrivals).toHaveTextContent('4 / 5');
    expect(arrivals).toHaveTextContent('1 neprišiel');
    expect(screen.getByTestId('tile-departures')).toHaveTextContent('4 / 4');
    expect(screen.getByTestId('tile-inhouse')).toHaveTextContent('112');
    expect(screen.getByTestId('tile-inhouse')).toHaveTextContent('ráno 112, +4 −4');
    expect(screen.getByTestId('tile-occupancy')).toHaveTextContent('81 %');
    expect(screen.getByTestId('tile-occupancy')).toHaveTextContent('112 z 138 použiteľných lôžok');
    expect(screen.getByTestId('tile-free')).toHaveTextContent('26');
    expect(screen.getByTestId('tile-free')).toHaveTextContent('blokovaných 6');
    const rows = screen.getAllByTestId('building-row');
    expect(rows).toHaveLength(3);
    expect(rows[2]).toHaveTextContent('Komplex spolu');
    expect(screen.getByTestId('occupancy-explain')).toHaveTextContent('obsadené lôžka / (lôžka − blokované)');

    expect(screen.getAllByTestId('forecast-bar')).toHaveLength(14);
    expect(screen.getAllByTestId('forecast-label')).toHaveLength(3);
    expect(screen.getByTestId('forecast-chart')).toHaveAttribute('aria-label', expect.stringContaining('najvyššia 90 %'));
    expect(screen.getAllByTestId('forecast-bar')[0]).toHaveAttribute('title', expect.stringContaining('83 %'));
    const weekend = screen.getAllByTestId('forecast-day').filter((d) => d.dataset.weekend === 'true');
    expect(weekend).toHaveLength(4);

    expect(screen.getByTestId('close-noshow-finding')).toHaveTextContent('R-2026-0148');
    expect(screen.getByTestId('close-noshow')).toHaveTextContent('nastaví stav Neprišiel');
    expect(screen.getByRole('button', { name: 'Uzavrieť deň' })).toBeEnabled();
  });

  it('tlač: hlavička s recepciou, dňom a časom, A4 na výšku, menu sa skryje', async () => {
    mock(CLEAN);
    const print = vi.spyOn(window, 'print').mockImplementation(() => {});
    renderPage();
    await screen.findByTestId('tile-arrivals');
    await userEvent.click(screen.getByRole('button', { name: 'Tlačiť report' }));
    expect(print).toHaveBeenCalledOnce();
    const head = screen.getByTestId('report-print-head');
    expect(head).toHaveTextContent('18. 11. 2026');
    expect(head).toHaveTextContent(/Vytlačené: \S+/);
    const css = document.querySelector('[data-testid="report-print-area"] style')?.textContent ?? '';
    expect(css).toContain('size: A4 portrait');
    expect(css).toContain('body * { visibility: hidden; }');
    expect(screen.getByTestId('report-print-area')).toContainElement(screen.getByTestId('tile-arrivals'));
    expect(screen.getByTestId('report-print-area')).not.toContainElement(screen.getByTestId('close-checks'));
  });

  it('archív: zoznam uzavretých dní a uložený report len na čítanie', async () => {
    mock(CLEAN);
    mockArchive();
    vi.spyOn(window, 'print').mockImplementation(() => {});
    renderPage();
    await screen.findByTestId('tile-arrivals');
    await userEvent.click(screen.getByRole('button', { name: 'Archív reportov' }));
    expect(await screen.findAllByTestId('archive-item')).toHaveLength(1);
    await userEvent.click(screen.getByRole('button', { name: 'Zobraziť report' }));
    expect(await screen.findByTestId('archive-readonly')).toBeInTheDocument();
    expect(screen.getByTestId('tile-arrivals')).toHaveTextContent('4 / 5');
    expect(screen.getByTestId('report-print-head')).toHaveTextContent('17. 11. 2026');
    expect(screen.queryByRole('button', { name: 'Uzavrieť deň' })).toBeNull();
    await userEvent.click(screen.getByRole('button', { name: 'Tlačiť report' }));
    expect(window.print).toHaveBeenCalledOnce();
  });

  it('náhľad s blokujúcim zistením: zoznamy s odkazmi, tlačidlo neaktívne', async () => {
    mock(BLOCKING);
    renderPage();
    expect(await screen.findByTestId('close-blocking-finding')).toHaveTextContent('bez pridelenej izby');
    expect(screen.getByTestId('close-info-finding')).toHaveTextContent('bez ošetrujúceho lekára');
    expect(screen.getAllByRole('link', { name: 'Otvoriť kartu' })[0]).toHaveAttribute('href', '/reservations/7');
    expect(screen.getAllByRole('link', { name: 'Plán' })[0]).toHaveAttribute('href', '/plan');
    expect(screen.getByRole('button', { name: 'Uzavrieť deň' })).toBeDisabled();
  });

  it('úspešná uzávierka: dialóg s dátumom, nový deň, úplná synchronizácia', async () => {
    mock(CLEAN);
    const sync = vi.spyOn(syncEngine, 'fullSync').mockResolvedValue(undefined as never);
    renderPage();
    const btn = await screen.findByRole('button', { name: 'Uzavrieť deň' });
    await waitFor(() => expect(btn).toBeEnabled());
    await userEvent.click(btn);
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveTextContent('18. 11. 2026');
    await userEvent.click(await screen.findAllByRole('button', { name: 'Uzavrieť deň' }).then((b) => b[b.length - 1]));
    expect(await screen.findByTestId('close-done')).toHaveTextContent('19. 11. 2026');
    expect(sync).toHaveBeenCalledWith('business-day', { receptionId: 2 });
  });

  it('409: niekto iný medzitým uzavrel deň', async () => {
    mock(CLEAN, () => HttpResponse.json({ status: 409, code: 'BUSINESS_DAY_CHANGED' }, { status: 409 }));
    renderPage();
    const btn = await screen.findByRole('button', { name: 'Uzavrieť deň' });
    await waitFor(() => expect(btn).toBeEnabled());
    await userEvent.click(btn);
    await userEvent.click((await screen.findAllByRole('button', { name: 'Uzavrieť deň' })).at(-1) as HTMLElement);
    expect(await screen.findByTestId('close-error')).toHaveTextContent('medzitým uzavrel niekto iný');
    expect(screen.queryByTestId('close-done')).toBeNull();
  });

  it('offline: všetko neaktívne s vysvetlením', async () => {
    connection.reset('offline');
    mock(CLEAN);
    renderPage();
    expect(await screen.findByTestId('close-offline')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Uzavrieť deň' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Znova skontrolovať' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Archív reportov' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Tlačiť report' })).toBeDisabled();
  });
});
