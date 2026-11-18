import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { connection } from '../../shared/offline';
import { demoReceptions } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { UsersPage } from './UsersPage';

const USERS = [
  { id: 10, username: 'recepcna.demo', displayName: 'Recepčná Ukážková', status: 'ACTIVE', roles: [{ roleCode: 'RECEPTIONIST', roleName: 'Recepčná', receptionId: 2 }] },
  { id: 11, username: 'zamknuty.demo', displayName: 'Zamknutý Ukážkový', status: 'LOCKED', roles: [] },
];

function mock() {
  server.use(
    http.get('/api/admin/users', () => HttpResponse.json(USERS)),
    http.get('/api/admin/roles', () => HttpResponse.json([{ code: 'RECEPTIONIST', name: 'Recepčná' }, { code: 'DOCTOR', name: 'Lekár' }])),
    http.get('/api/catalog/centres', () => HttpResponse.json([{ id: 1, code: 'C1', name: 'Hlavná budova', active: true }])),
    http.get('/api/catalog/receptions', () => HttpResponse.json(demoReceptions)),
  );
}

afterEach(() => connection.reset('online'));

describe('UsersPage', () => {
  it('zoznam sa filtruje podľa mena a stavu', async () => {
    mock();
    renderWithProviders(<UsersPage />);
    expect(await screen.findAllByTestId('user-row')).toHaveLength(2);
    await userEvent.type(screen.getByLabelText('Meno alebo používateľské meno'), 'zamknut');
    expect(screen.getAllByTestId('user-row')).toHaveLength(1);
    await userEvent.clear(screen.getByLabelText('Meno alebo používateľské meno'));
    await userEvent.selectOptions(screen.getByLabelText('Stav'), 'ACTIVE');
    expect(screen.getAllByTestId('user-row')).toHaveLength(1);
    expect(screen.getByTestId('user-row')).toHaveTextContent('Recepčná Ukážková');
  });

  it('založenie používateľa zobrazí jednorazové heslo', async () => {
    mock();
    let body: unknown;
    server.use(
      http.post('/api/admin/users', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ user: { id: 12, username: 'novy.demo', displayName: 'Nový Ukážkový' }, oneTimePassword: 'Jednorazove-123' }, { status: 201 });
      }),
    );
    renderWithProviders(<UsersPage />);
    await userEvent.click(await screen.findByRole('button', { name: '+ Nový používateľ' }));
    await userEvent.type(screen.getByLabelText('Používateľské meno'), 'novy.demo');
    await userEvent.type(screen.getByLabelText('Meno a priezvisko'), 'Nový Ukážkový');
    await userEvent.click(screen.getByRole('button', { name: 'Založiť' }));
    expect(await screen.findByTestId('one-time-password')).toHaveTextContent('Jednorazove-123');
    expect(body).toMatchObject({ username: 'novy.demo', displayName: 'Nový Ukážkový' });
  });

  it('chyba validácie sa zobrazí pri poli', async () => {
    mock();
    server.use(
      http.post('/api/admin/users', () =>
        HttpResponse.json(
          { status: 400, code: 'VALIDATION_FAILED', fieldViolations: [{ field: 'username', message: 'Používateľské meno už existuje' }] },
          { status: 400 },
        ),
      ),
    );
    renderWithProviders(<UsersPage />);
    await userEvent.click(await screen.findByRole('button', { name: '+ Nový používateľ' }));
    await userEvent.type(screen.getByLabelText('Používateľské meno'), 'recepcna.demo');
    await userEvent.type(screen.getByLabelText('Meno a priezvisko'), 'X');
    await userEvent.click(screen.getByRole('button', { name: 'Založiť' }));
    expect(await screen.findByText('Používateľské meno už existuje')).toBeInTheDocument();
    expect(screen.queryByTestId('one-time-password')).toBeNull();
  });

  it('priradenie roly s rozsahom a platnosťou', async () => {
    mock();
    let body: { roles: unknown[] } | undefined;
    server.use(
      http.put('/api/admin/users/:id/roles', async ({ request }) => {
        body = (await request.json()) as { roles: unknown[] };
        return HttpResponse.json(USERS[1]);
      }),
    );
    renderWithProviders(<UsersPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Roly Zamknutý Ukážkový' }));
    await userEvent.click(screen.getByRole('button', { name: 'Pridať rolu' }));
    const row = screen.getByTestId('role-row');
    await waitFor(() => expect(within(row).getByRole('option', { name: 'Lekár' })).toBeInTheDocument());
    await userEvent.selectOptions(within(row).getByLabelText('Rola'), 'DOCTOR');
    await userEvent.selectOptions(within(row).getByLabelText('Stredisko'), '1');
    await userEvent.selectOptions(within(row).getByLabelText('Recepcia'), '2');
    await userEvent.type(within(row).getByLabelText('Platné od'), '2026-12-01');
    await userEvent.type(within(row).getByLabelText('Platné do'), '2027-12-01');
    await userEvent.click(screen.getByRole('button', { name: 'Uložiť' }));
    await waitFor(() => expect(body).toBeDefined());
    expect(body?.roles).toEqual([{ roleCode: 'DOCTOR', centreId: 1, receptionId: 2, validFrom: '2026-12-01', validTo: '2027-12-01' }]);
  });

  it('reset hesla zobrazí heslo raz s upozornením a tlačidlom Kopírovať', async () => {
    mock();
    server.use(http.post('/api/admin/users/:id/reset-password', () => HttpResponse.json({ oneTimePassword: 'Reset-456' })));
    renderWithProviders(<UsersPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Reset hesla Recepčná Ukážková' }));
    expect(await screen.findByTestId('one-time-password')).toHaveTextContent('Reset-456');
    expect(screen.getByTestId('password-warning')).toHaveTextContent('už sa nezobrazí');
    expect(screen.getByRole('button', { name: 'Kopírovať' })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Zavrieť' }));
    await waitFor(() => expect(screen.queryByText('Reset-456')).toBeNull());
  });

  it('odomknutie zamknutého účtu', async () => {
    mock();
    let called = false;
    server.use(http.post('/api/admin/users/:id/unlock', () => { called = true; return HttpResponse.json({ ...USERS[1], status: 'ACTIVE' }); }));
    renderWithProviders(<UsersPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Odomknúť Zamknutý Ukážkový' }));
    expect(await screen.findByTestId('admin-notice')).toBeInTheDocument();
    expect(called).toBe(true);
  });

  it('offline: upozornenie, zoznam sa nenačíta, nový používateľ neaktívny', async () => {
    connection.reset('offline');
    mock();
    renderWithProviders(<UsersPage />);
    expect(await screen.findByTestId('admin-offline')).toBeInTheDocument();
    expect(screen.queryAllByTestId('user-row')).toHaveLength(0);
    expect(screen.getByRole('button', { name: '+ Nový používateľ' })).toBeDisabled();
  });
});
