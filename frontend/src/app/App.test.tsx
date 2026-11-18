import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { SESSION_DATASET, SESSION_KEY } from '../features/auth/loginFlow';
import { clearSessionExpired } from '../features/auth/session';
import { platform } from '../shared/platform';
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

afterEach(async () => {
  clearSessionExpired();
  await platform.vault.lock();
  for (const u of ['recepcna.demo', 'x']) await platform.vault.wipe(u);
});

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
    expect(await screen.findByTestId('plan-page')).toBeInTheDocument();
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

  it('server nedostupný a na tomto počítači nie je trezor', async () => {
    mockApi(null);
    server.use(http.post('/api/auth/login', () => HttpResponse.error()));
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Používateľské meno/), 'x');
    await user.type(screen.getByLabelText(/^Heslo/), 'y');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Na tomto počítači ste sa ešte neprihlásili online.',
    );
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
    expect(await screen.findByTestId('plan-page')).toBeInTheDocument();
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

async function typeCredentials(username: string, password: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText(/^Používateľské meno/), username);
  await user.type(screen.getByLabelText(/^Heslo/), password);
  await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
  return user;
}

async function seedVault(username: string, password: string) {
  await platform.vault.create(username, password);
  await platform.vault.put(SESSION_DATASET, SESSION_KEY, receptionist);
  await platform.vault.lock();
}

describe('lokálny trezor a prihlásenie bez servera', () => {
  const online = () => {
    let loggedIn = false;
    server.use(
      http.get('/api/me', () => (loggedIn ? HttpResponse.json(receptionist) : HttpResponse.json({}, { status: 401 }))),
      http.post('/api/auth/login', () => {
        loggedIn = true;
        return HttpResponse.json(receptionist);
      }),
      http.get('/api/catalog/receptions', () => HttpResponse.json([])),
    );
  };

  it('prvé online prihlásenie vytvorí trezor a uloží doňho profil', async () => {
    online();
    renderApp();
    await typeCredentials('recepcna.demo', 'heslo-demo');
    await screen.findByTestId('plan-page');
    expect(await platform.vault.exists('recepcna.demo')).toBe(true);
    expect((await platform.vault.get(SESSION_DATASET, SESSION_KEY))?.value).toEqual(receptionist);
  });

  it('online prihlásenie s iným heslom založí nový trezor (dáta zo starého zmiznú)', async () => {
    await seedVault('recepcna.demo', 'stare-heslo');
    await platform.vault.unlock('recepcna.demo', 'stare-heslo');
    await platform.vault.put('plan', '1', { a: 1 });
    await platform.vault.lock();
    online();
    renderApp();
    await typeCredentials('recepcna.demo', 'nove-heslo');
    await screen.findByTestId('plan-page');
    expect(await platform.vault.get('plan', '1')).toBeNull();
    await platform.vault.lock();
    expect(await platform.vault.unlock('recepcna.demo', 'nove-heslo')).toBe('ok');
  });

  it('online prihlásenie s rovnakým heslom zachová dáta v trezore', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    await platform.vault.unlock('recepcna.demo', 'heslo-demo');
    await platform.vault.put('plan', '1', { a: 1 });
    await platform.vault.lock();
    online();
    renderApp();
    await typeCredentials('recepcna.demo', 'heslo-demo');
    await screen.findByTestId('plan-page');
    expect((await platform.vault.get('plan', '1'))?.value).toEqual({ a: 1 });
  });

  it('offline prihlásenie so správnym heslom otvorí aplikáciu z trezora', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    server.use(
      http.get('/api/me', () => HttpResponse.json({}, { status: 401 })),
      http.post('/api/auth/login', () => HttpResponse.error()),
      http.get('/api/catalog/receptions', () => HttpResponse.error()),
    );
    renderApp();
    await typeCredentials('recepcna.demo', 'heslo-demo');
    expect(await screen.findByTestId('plan-page')).toBeInTheDocument();
    expect(screen.getByText('Recepčná Ukážková')).toBeInTheDocument();
  });

  it('offline prihlásenie so zlým heslom zobrazí chybu', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    mockApi(null);
    server.use(http.post('/api/auth/login', () => HttpResponse.error()));
    renderApp();
    await typeCredentials('recepcna.demo', 'zle-heslo');
    expect(await screen.findByRole('alert')).toHaveTextContent('Nesprávne meno alebo heslo.');
  });

  it('offline: po 5 zlých pokusoch sa prihlásenie dočasne zablokuje', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    mockApi(null);
    server.use(http.post('/api/auth/login', () => HttpResponse.error()));
    renderApp();
    const user = await typeCredentials('recepcna.demo', 'zle-heslo');
    for (let i = 0; i < 4; i++) {
      await screen.findByRole('alert');
      await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    }
    await waitFor(async () => {
      await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
      expect(screen.getByRole('alert')).toHaveTextContent('Príliš veľa nesprávnych pokusov');
    });
  });

  it('offline bez trezoru na tomto počítači zobrazí vysvetlenie', async () => {
    mockApi(null);
    server.use(http.post('/api/auth/login', () => HttpResponse.error()));
    renderApp();
    await typeCredentials('recepcna.demo', 'heslo-demo');
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Na tomto počítači ste sa ešte neprihlásili online.',
    );
  });

  it('zlé heslo na serveri (401) trezor neskúša ani nemení', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    mockApi(null);
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ code: 'INVALID_CREDENTIALS' }, { status: 401 })),
    );
    renderApp();
    await typeCredentials('recepcna.demo', 'ine-heslo');
    expect(await screen.findByRole('alert')).toHaveTextContent('Nesprávne meno alebo heslo.');
    expect(await platform.vault.exists('recepcna.demo')).toBe(true);
    expect(await platform.vault.unlock('recepcna.demo', 'heslo-demo')).toBe('ok');
  });

  it('deaktivovaný účet vymaže trezor', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    mockApi(null);
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ code: 'ACCOUNT_DISABLED' }, { status: 403 })),
    );
    renderApp();
    await typeCredentials('recepcna.demo', 'heslo-demo');
    expect(await screen.findByRole('alert')).toHaveTextContent('Účet je deaktivovaný.');
    expect(await platform.vault.exists('recepcna.demo')).toBe(false);
  });

  it('zmena hesla prebalí trezor (nové heslo odomkne, staré nie)', async () => {
    await seedVault('recepcna.demo', 'stare');
    await platform.vault.unlock('recepcna.demo', 'stare');
    await platform.vault.put('plan', '1', { a: 1 });
    let changed = false;
    server.use(
      http.get('/api/me', () => HttpResponse.json({ ...receptionist, mustChangePassword: !changed })),
      http.post('/api/me/password', () => {
        changed = true;
        return new HttpResponse(null, { status: 204 });
      }),
      http.get('/api/catalog/receptions', () => HttpResponse.json([])),
    );
    renderApp();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText(/^Súčasné heslo/), 'stare');
    await user.type(screen.getByLabelText(/^Nové heslo(?! znova)/), 'nove-heslo-1');
    await user.type(screen.getByLabelText(/^Nové heslo znova/), 'nove-heslo-1');
    await user.click(screen.getByRole('button', { name: 'Zmeniť heslo' }));
    await screen.findByTestId('plan-page');
    await platform.vault.lock();
    expect(await platform.vault.unlock('recepcna.demo', 'stare')).toBe('wrong-password');
    expect(await platform.vault.unlock('recepcna.demo', 'nove-heslo-1')).toBe('ok');
    expect((await platform.vault.get('plan', '1'))?.value).toEqual({ a: 1 });
  });

  it('„Odhlásiť a vymazať údaje z tohto počítača“ zmaže trezor a odhlási', async () => {
    await seedVault('recepcna.demo', 'heslo-demo');
    await platform.vault.unlock('recepcna.demo', 'heslo-demo');
    mockApi(receptionist);
    server.use(http.post('/api/auth/logout', () => new HttpResponse(null, { status: 204 })));
    renderApp();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Profil používateľa' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Odhlásiť a vymazať údaje z tohto počítača' }));
    await user.click(await screen.findByRole('button', { name: 'Odhlásiť a vymazať' }));
    expect(await screen.findByLabelText(/^Používateľské meno/)).toBeInTheDocument();
    expect(await platform.vault.exists('recepcna.demo')).toBe(false);
  });
});

describe('menu a trasy podľa oprávnení', () => {
  it('recepčná: plán, karty, príchody, uzávierka, hostia; úvod = plán', async () => {
    mockApi(receptionist);
    const { router } = renderApp();
    expect(await screen.findByTestId('plan-page')).toBeInTheDocument();
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
    // zoznam v menu (plán má vlastný natívny výber recepcie s rovnakými názvami)
    const list = within(await screen.findByRole('listbox', { hidden: true }));
    expect(await list.findByRole('option', { name: 'Recepcia A', hidden: true })).toBeInTheDocument();
    expect(list.getByRole('option', { name: 'Recepcia B', hidden: true })).toBeInTheDocument();
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
    await screen.findByTestId('plan-page');
    await waitFor(() => expect(businessDayCalls).toBe(1));

    expired = true;
    await client.invalidateQueries({ queryKey: ['business-day'] });
    expect(await screen.findByText('Relácia vypršala')).toBeInTheDocument();
    expect(screen.getByTestId('plan-page')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/^Heslo/), 'heslo-demo');
    await user.click(screen.getByRole('button', { name: 'Prihlásiť sa' }));
    await waitFor(() => expect(screen.queryByText('Relácia vypršala')).not.toBeInTheDocument());
    await waitFor(() => expect(businessDayCalls).toBeGreaterThan(2));
    expect(screen.getByTestId('plan-page')).toBeInTheDocument();
  });
});
