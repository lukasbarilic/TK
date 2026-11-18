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

const BLOCKING = {
  receptionId: 2, businessDate: '2026-11-18', canClose: false,
  blocking: [{ ruleCode: 'NO_ROOM', severity: 'BLOCKING', reservationId: 7, message: 'Ubytovaný hosť bez pridelenej izby' }],
  info: [{ ruleCode: 'NO_DOCTOR', severity: 'INFO', reservationId: 8, message: 'Hosť bez ošetrujúceho lekára' }],
};
const CLEAN = { receptionId: 2, businessDate: '2026-11-18', canClose: true, blocking: [], info: BLOCKING.info };

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

describe('BusinessDayClosePage', () => {
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
  });
});
