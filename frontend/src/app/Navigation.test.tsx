import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { BUSINESS_DATE, sampleCard, samplePlan } from '../features/plan/testData';
import { originState } from '../shared/navigation/origin';
import { connection } from '../shared/offline';
import { platform } from '../shared/platform';
import { mockApi, receptionist } from '../shared/test/handlers';
import { renderWithProviders } from '../shared/test/render';
import { server } from '../shared/test/server';
import { createQueryClient } from './queryClient';
import { routes } from './router';

function renderApp(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  renderWithProviders(<RouterProvider router={router} />, createQueryClient());
  return router;
}

beforeEach(async () => {
  mockApi(receptionist);
  server.use(
    http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
    http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
    http.put('/api/me/preferences/:key', async ({ request, params }) =>
      HttpResponse.json({ key: params.key, value: ((await request.json()) as { value: unknown }).value, version: 1 }),
    ),
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: BUSINESS_DATE })),
    http.get('/api/accommodation-plan', () => HttpResponse.json(samplePlan())),
    http.get('/api/reservations/:id/stays', () => HttpResponse.json([])),
    http.get('/api/reservations/:id', () => HttpResponse.json(sampleCard)),
  );
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('navigácia Späť', () => {
  it('návrat z karty do plánu obnoví filtre a zoom; drobčeky ukazujú pôvod', async () => {
    const router = renderApp('/plan');
    await screen.findAllByTestId('plan-bar');
    await userEvent.click(screen.getByText('Široké'));
    await userEvent.click(screen.getByLabelText('Len voľné izby'));
    await router.navigate('/reservations/912', { state: originState('Plán ubytovania', '/plan') });

    const header = await screen.findByTestId('page-header');
    expect(header).toHaveTextContent('Plán ubytovania');
    await userEvent.click(screen.getByRole('button', { name: /Späť/ }));

    await screen.findAllByTestId('plan-grid');
    expect(router.state.location.pathname).toBe('/plan');
    expect(screen.getByRole('button', { name: 'Široké' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Len voľné izby')).toBeChecked();
  });

  it('Alt+← vráti na predchádzajúcu obrazovku, Alt+→ dopredu', async () => {
    const router = renderApp('/plan');
    await screen.findAllByTestId('plan-bar');
    await router.navigate('/reservations/912');
    await screen.findByTestId('page-header');
    fireEvent.keyDown(window, { key: 'ArrowLeft', altKey: true });
    await waitFor(() => expect(router.state.location.pathname).toBe('/plan'));
    fireEvent.keyDown(window, { key: 'ArrowRight', altKey: true });
    await waitFor(() => expect(router.state.location.pathname).toBe('/reservations/912'));
  });

  it('bočné tlačidlo myši Back vráti v histórii', async () => {
    const router = renderApp('/plan');
    await screen.findAllByTestId('plan-bar');
    await router.navigate('/reservations/912');
    await screen.findByTestId('page-header');
    fireEvent.mouseUp(window, { button: 3 });
    await waitFor(() => expect(router.state.location.pathname).toBe('/plan'));
  });

  it('priamy vstup na kartu bez histórie: Späť vedie na zoznam kariet', async () => {
    server.use(
      http.get('/api/reservations', () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0 })),
    );
    const router = renderApp('/reservations/912');
    await userEvent.click(await screen.findByRole('button', { name: /Späť/ }));
    await waitFor(() => expect(router.state.location.pathname).toBe('/reservations'));
  });
});
