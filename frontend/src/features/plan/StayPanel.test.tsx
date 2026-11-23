import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { resetHandledEvents } from '../../shared/offline/handledEvents';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { PlanPage } from './PlanPage';
import { StayPanel } from './StayPanel';
import { BUSINESS_DATE, sampleCard, samplePlan } from './testData';
import { DEFAULT_VIEW, type BarVM } from './types';

const stay: BarVM = {
  id: 'stay:812', type: 'STAY', bedId: 11, from: '2026-11-19', to: '2026-11-22',
  startCol: 2, endCol: 5, clippedStart: false, clippedEnd: false, label: 'Testovský A.', status: 'CHECKED_IN', reservationId: 912,
} as BarVM;

const Probe = () => {
  const loc = useLocation();
  const navigate = useNavigate();
  return (
    <div>
      <span data-testid="path">{loc.pathname}</span>
      <span data-testid="origin">{JSON.stringify((loc.state as { origin?: unknown } | null)?.origin)}</span>
      <button onClick={() => void navigate(-1)}>Späť</button>
    </div>
  );
};

function renderPanel(canOpenCard: boolean) {
  return renderWithProviders(
    <MemoryRouter initialEntries={['/plan']}>
      <Routes>
        <Route path="/plan" element={<StayPanel bar={stay} roomLabel="ALF 101" bedLabel="1" canOpenCard={canOpenCard} onClose={() => undefined} />} />
        <Route path="/reservations/:id" element={<Probe />} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(async () => {
  resetHandledEvents();
  sessionStorage.clear();
  await platform.vault.create('recepcna.demo', 'heslo-demo');
  server.use(http.get('/api/reservations/:id', () => HttpResponse.json(sampleCard)));
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('StayPanel – odkaz na kartu', () => {
  it('číslo rezervácie je odkaz na detail karty s pôvodom plánu, použiteľný klávesnicou', async () => {
    renderPanel(true);
    const link = await screen.findByRole('link', { name: /R-2026-0912/ });
    expect(link).toHaveAttribute('href', '/reservations/912');
    await userEvent.tab();
    // fokus sa dostane na odkaz (Tab) a Enter ho otvorí
    link.focus();
    await userEvent.keyboard('{Enter}');
    expect(await screen.findByTestId('path')).toHaveTextContent('/reservations/912');
    expect(JSON.parse(screen.getByTestId('origin').textContent as string)).toEqual({ label: 'Plán ubytovania', to: '/plan' });
  });

  it('karta s výhradným užívaním izby má v paneli štítok', async () => {
    server.use(http.get('/api/reservations/:id', () => HttpResponse.json({ ...sampleCard, exclusiveRoomUse: true })));
    renderPanel(true);
    expect(await screen.findByTestId('panel-exclusive')).toHaveTextContent('Výhradné užívanie izby');
  });

  it('bez výhradného užívania izby štítok nie je', async () => {
    renderPanel(true);
    await screen.findByRole('link', { name: /R-2026-0912/ });
    expect(screen.queryByTestId('panel-exclusive')).toBeNull();
  });

  it('bez oprávnenia RESERVATION_VIEW je číslo obyčajný text', async () => {
    renderPanel(false);
    expect(await screen.findByText('R-2026-0912')).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('offline bez karty v trezore ukáže „nie sú uložené“ a žiadny odkaz', async () => {
    connection.reset('offline');
    renderPanel(true);
    expect(await screen.findByText(/nie sú na tomto počítači uložené/i)).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('offline s kartou v trezore odkaz funguje', async () => {
    await platform.vault.put(DS.reservation, '912', sampleCard, { syncedAt: '2026-11-17T21:47:00.000Z' });
    connection.reset('offline');
    renderPanel(true);
    expect(await screen.findByRole('link', { name: /R-2026-0912/ })).toHaveAttribute('href', '/reservations/912');
  });

  it('pásik blokácie nemá kartu, a teda ani odkaz', () => {
    renderWithProviders(
      <MemoryRouter>
        <StayPanel bar={{ ...stay, id: 'block:5', type: 'ROOM_BLOCK', reservationId: undefined, bedId: null }} roomLabel="ALF 101" bedLabel="1" canOpenCard onClose={() => undefined} />
      </MemoryRouter>,
    );
    expect(screen.queryByTestId('reservation-card')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });
});

describe('PlanPage → karta → Späť', () => {
  it('odkaz z panela otvorí kartu s pôvodom /plan a Späť obnoví rozsah plánu', async () => {
    sessionStorage.setItem('plan.session', JSON.stringify({ filters: DEFAULT_VIEW, offset: 3, scrollTop: 0, scrollLeft: 0 }));
    const froms: string[] = [];
    server.use(
      http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, code: 'R2', name: 'Recepcia B', active: true }])),
      http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: BUSINESS_DATE })),
      http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
      http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
      http.get('/api/accommodation-plan', ({ request }) => {
        froms.push(new URL(request.url).searchParams.get('from') as string);
        return HttpResponse.json(samplePlan());
      }),
    );
    renderWithProviders(
      <MemoryRouter initialEntries={['/plan']}>
        <Routes>
          <Route path="/plan" element={<PlanPage me={receptionist} />} />
          <Route path="/reservations/:id" element={<Probe />} />
        </Routes>
      </MemoryRouter>,
    );
    const bars = await screen.findAllByTestId('plan-bar');
    const stayBarEl = bars.find((b) => b.getAttribute('data-bar-id') === 'stay:812') as HTMLElement;
    await userEvent.click(stayBarEl);
    const panel = await screen.findByTestId('stay-panel');
    await userEvent.click(await within(panel).findByRole('link', { name: /R-2026-0912/ }));
    expect(await screen.findByTestId('path')).toHaveTextContent('/reservations/912');
    expect(JSON.parse(screen.getByTestId('origin').textContent as string)).toEqual({ label: 'Plán ubytovania', to: '/plan' });
    const firstFrom = froms[0];
    await userEvent.click(screen.getByRole('button', { name: 'Späť' }));
    await screen.findAllByTestId('plan-bar');
    await waitFor(() => expect(froms.length).toBeGreaterThan(1));
    expect(froms[froms.length - 1]).toBe(firstFrom);
  });
});
