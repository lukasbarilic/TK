import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { clearSessionExpired } from '../features/auth/session';
import { admin, doctor, mockApi, receptionist } from '../shared/test/handlers';
import { renderWithProviders } from '../shared/test/render';
import { server } from '../shared/test/server';
import { createQueryClient } from './queryClient';
import { routes } from './router';

function renderApp(path = '/') {
  const client = createQueryClient();
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  renderWithProviders(<RouterProvider router={router} />, client);
  return { client, router };
}

const menu = () => within(screen.getByTestId('main-menu'));

afterEach(() => clearSessionExpired());

describe('prihlásenie', () => {
  it('úspešné prihlásenie načíta GET /api/me a otvorí úvodnú obrazovku', async () => {
    let loggedIn = false;
    server.use(
      http.get('/api/me', () =>
        loggedIn ? HttpResponse.json(receptionist) : HttpResponse.json({}, { status: 401 }),
      ),
      http.post('/api/auth/login', async ({ request }) => {
        expect(await request.json()).toEqual({ username: 'recepcna.demo', password: 'heslo-demo' });
        loggedIn = true;
        return HttpResponse.json(receptionist);
      }),
      http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, name: 'Recepcia B', active: true }])),
      http.get('/api/receptions/2/business-day', () =>
        HttpResponse.json({ receptionId: 2, businessDate: '2026-11-18' }),
      ),
    );
    const { router } = renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Používateľské meno/), 'recepcna.demo');
    await user.type(screen.getByLabelText(/^Heslo/), 'heslo-demo');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    expect(await screen.findByText('Plán ubytovania – pripravuje sa')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/plan');
  });

  it.each([
    ['zlé meno/heslo', { status: 401, body: { code: 'INVALID_CREDENTIALS' } }, 'Nesprávne meno alebo heslo.'],
    ['zamknutý účet', { status: 423, body: { code: 'ACCOUNT_LOCKED' } }, /Účet je zamknutý\./],
  ])('chyba: %s', async (_n, res, text) => {
    mockApi(null);
    server.use(http.post('/api/auth/login', () => HttpResponse.json(res.body, { status: res.status })));
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Používateľské meno/), 'x');
    await user.type(screen.getByLabelText(/^Heslo/), 'y');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(text);
  });

  it('zamknutý účet zobrazí čas odomknutia, ak ho API vráti', async () => {
    mockApi(null);
    server.use(
      http.post('/api/auth/login', () =>
        HttpResponse.json({ code: 'ACCOUNT_LOCKED', lockedUntil: '2026-11-18T10:30:00Z' }, { status: 423 }),
      ),
    );
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Používateľské meno/), 'x');
    await user.type(screen.getByLabelText(/^Heslo/), 'y');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Účet je zamknutý do .*2026/);
  });

  it('server nedostupný', async () => {
    mockApi(null);
    server.use(http.post('/api/auth/login', () => HttpResponse.error()));
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Používateľské meno/), 'x');
    await user.type(screen.getByLabelText(/^Heslo/), 'y');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Server nie je dostupný');
  });

  it('pri mustChangePassword zobrazí len zmenu hesla a potom pokračuje do aplikácie', async () => {
    let changed = false;
    server.use(
      http.get('/api/me', () =>
        HttpResponse.json({ ...receptionist, mustChangePassword: !changed }),
      ),
      http.post('/api/me/password', async ({ request }) => {
        expect(await request.json()).toEqual({ currentPassword: 'stare', newPassword: 'nove-heslo-1' });
        changed = true;
        return new HttpResponse(null, { status: 204 });
      }),
      http.get('/api/catalog/receptions', () => HttpResponse.json([])),
    );
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Súčasné heslo/), 'stare');
    expect(screen.queryByTestId('main-menu')).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/^Nové heslo(?! znova)/), 'nove-heslo-1');
    await user.type(screen.getByLabelText(/^Nové heslo znova/), 'nove-heslo-1');
    await user.click(screen.getByRole('button', { name: 'Zmeniť heslo' }));
    expect(await screen.findByText('Plán ubytovania – pripravuje sa')).toBeInTheDocument();
  });

  it('odhlásenie zavolá POST /api/auth/logout a vráti na prihlásenie', async () => {
    mockApi(receptionist);
    let called = false;
    server.use(
      http.post('/api/auth/logout', () => {
        called = true;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Odhlásiť' }));
    expect(await screen.findByLabelText(/^Používateľské meno/)).toBeInTheDocument();
    expect(called).toBe(true);
  });
});

describe('menu a trasy podľa oprávnení', () => {
  it('recepčná: plán, karty, príchody, uzávierka, hostia; úvod = plán', async () => {
    mockApi(receptionist);
    const { router } = renderApp();
    expect(await screen.findByText('Plán ubytovania – pripravuje sa')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/plan');
    expect(menu().getAllByRole('link').map((l) => l.textContent)).toEqual([
      'Plán ubytovania', 'Rezervačné karty', 'Príchody a odchody', 'Nočná uzávierka', 'Zoznam hostí',
    ]);
  });

  it('lekár: len zoznam hostí; úvod = zoznam hostí', async () => {
    mockApi(doctor);
    const { router } = renderApp();
    expect(await screen.findByText('Zoznam hostí – pripravuje sa')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/guests');
    expect(menu().getAllByRole('link').map((l) => l.textContent)).toEqual(['Zoznam hostí']);
    expect(screen.queryByRole('combobox', { name: 'Recepcia' })).not.toBeInTheDocument();
  });

  it('admin: používatelia a audit; úvod = používatelia', async () => {
    mockApi(admin);
    const { router } = renderApp();
    expect(await screen.findByText('Používatelia – pripravuje sa')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin/users');
    expect(menu().getAllByRole('link').map((l) => l.textContent)).toEqual(['Používatelia', 'Audit']);
  });

  it('trasa bez oprávnenia zobrazí „Nemáte oprávnenie“', async () => {
    mockApi(doctor);
    renderApp('/admin/users');
    expect(await screen.findByRole('heading', { name: 'Nemáte oprávnenie' })).toBeInTheDocument();
  });
});

describe('recepcia a obchodný deň', () => {
  it('ponúkne len recepcie v rozsahu, predvolená je defaultReceptionId, ukáže jej obchodný deň', async () => {
    mockApi(receptionist);
    renderApp();
    const select = await screen.findByRole('combobox', { name: 'Recepcia' });
    await waitFor(() => expect(select).toHaveValue('Recepcia B'));
    await userEvent.setup().click(select);
    expect(screen.queryByRole('option', { name: 'Recepcia A', hidden: true })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('business-day')).toHaveTextContent(/18\.\s?11\.\s?2026/));
  });

  it('oprávnenie bez rozsahu (null) ponúkne všetky recepcie', async () => {
    mockApi({ ...receptionist, permissions: [{ code: 'PLAN_VIEW' }] });
    renderApp();
    const select = await screen.findByRole('combobox', { name: 'Recepcia' });
    await userEvent.setup().click(select);
    expect(await screen.findByRole('option', { name: 'Recepcia A', hidden: true })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Recepcia B', hidden: true })).toBeInTheDocument();
  });
});

describe('relácia vypršala', () => {
  it('401 zobrazí dialóg nad obrazovkou; po prihlásení sa dáta obnovia', async () => {
    mockApi(receptionist);
    let expired = false;
    let businessDayCalls = 0;
    server.use(
      http.get('/api/receptions/:id/business-day', () => {
        businessDayCalls++;
        return expired
          ? HttpResponse.json({}, { status: 401 })
          : HttpResponse.json({ receptionId: 2, businessDate: '2026-11-18' });
      }),
      http.post('/api/auth/login', async ({ request }) => {
        expect(await request.json()).toEqual({ username: 'recepcna.demo', password: 'heslo-demo' });
        expired = false;
        return HttpResponse.json(receptionist);
      }),
    );
    const { client } = renderApp();
    await screen.findByText('Plán ubytovania – pripravuje sa');
    await waitFor(() => expect(businessDayCalls).toBe(1));

    expired = true;
    await client.invalidateQueries({ queryKey: ['business-day'] });
    expect(await screen.findByText('Relácia vypršala')).toBeInTheDocument();
    expect(screen.getByText('Plán ubytovania – pripravuje sa')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Heslo/), 'heslo-demo');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    await waitFor(() => expect(screen.queryByText('Relácia vypršala')).not.toBeInTheDocument());
    await waitFor(() => expect(businessDayCalls).toBeGreaterThan(2));
    expect(screen.getByText('Plán ubytovania – pripravuje sa')).toBeInTheDocument();
  });
});
