import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import type { components } from '../../shared/api/schema';
import { connection } from '../../shared/offline';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { ReservationDetailPage } from './ReservationDetailPage';

type Me = components['schemas']['MeView'];

const editor: Me = { ...receptionist, permissions: [...(receptionist.permissions ?? []), { code: 'RESERVATION_EDIT', scopes: [{ receptionId: 2 }] }] };

const baseGuest: components['schemas']['GuestView'] = {
  id: 1, clientId: 140, clientVersion: 2, firstName: 'Žofia', lastName: 'Ľudová',
  birthDate: '1970-01-01', nationality: 'SK', email: 'zofia@exmaple.invalid', phone: '0900 000 000', primaryGuest: true,
};
const baseCard = {
  id: 912, reservationNumber: 'R-2026-0912', status: 'RESERVED' as const,
  arrivalDate: '2026-11-19', departureDate: '2026-11-22', version: 3, comments: [], guests: [baseGuest],
};

let card = baseCard;
let puts: unknown[] = [];

function mockServer() {
  puts = [];
  server.use(
    http.get('/api/reservations/:id', () => HttpResponse.json(card)),
    http.get('/api/reservations/:id/stays', () => HttpResponse.json([])),
    http.put('/api/clients/:id', async ({ request }) => {
      const body = (await request.json()) as { version: number } & Record<string, unknown>;
      puts.push(body);
      const g = card.guests[0];
      if (body.version !== g.clientVersion) return HttpResponse.json({ status: 409, code: 'CONFLICT' }, { status: 409 });
      const { version, ...fields } = body;
      const saved = { ...g, phone: undefined, ...fields, clientVersion: version + 1 };
      card = { ...card, guests: [saved] };
      return HttpResponse.json({ id: 140, version: saved.clientVersion });
    }),
  );
}

const renderCard = (me: Me = editor) =>
  renderWithProviders(
    <MemoryRouter>
      <ReservationDetailPage id={912} me={me} canSeeContacts />
    </MemoryRouter>,
  );

const openEdit = async () =>
  userEvent.click(await screen.findByRole('button', { name: 'Upraviť údaje hosťa Ľudová Žofia' }));

beforeEach(async () => {
  card = baseCard;
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('Oprava údajov hosťa', () => {
  it('opraví preklep v e-maile, pošle všetky údaje s verziou klienta a obnoví kartu', async () => {
    mockServer();
    renderCard();
    await openEdit();
    const form = await screen.findByTestId('guest-edit-form');
    expect(within(form).getByLabelText(/Meno/)).toHaveValue('Žofia');
    const email = within(form).getByLabelText('E-mail');
    await userEvent.clear(email);
    await userEvent.type(email, ' zofia@example.invalid ');
    await userEvent.clear(within(form).getByLabelText('Telefón'));
    await userEvent.click(within(form).getByRole('button', { name: 'Uložiť údaje' }));

    await waitFor(() =>
      expect(puts).toEqual([
        { version: 2, firstName: 'Žofia', lastName: 'Ľudová', birthDate: '1970-01-01', nationality: 'SK', email: 'zofia@example.invalid' },
      ]),
    );
    await waitFor(() => expect(screen.queryByTestId('guest-edit-form')).toBeNull());
    expect(await screen.findByTestId('guest-contact')).toHaveTextContent('zofia@example.invalid');
    expect(screen.getByTestId('guest-contact')).not.toHaveTextContent('0900');
  });

  it('neodošle formulár bez priezviska a s neplatným e-mailom či krajinou', async () => {
    mockServer();
    renderCard();
    await openEdit();
    const form = await screen.findByTestId('guest-edit-form');
    await userEvent.clear(within(form).getByLabelText(/Priezvisko/));
    const email = within(form).getByLabelText('E-mail');
    await userEvent.clear(email);
    await userEvent.type(email, 'nie-je-email');
    const nat = within(form).getByLabelText(/Štátna príslušnosť/);
    await userEvent.clear(nat);
    await userEvent.type(nat, '1');
    await userEvent.click(within(form).getByRole('button', { name: 'Uložiť údaje' }));

    expect(await within(form).findByText('Povinný údaj')).toBeInTheDocument();
    expect(within(form).getByText('Neplatný e-mail')).toBeInTheDocument();
    expect(within(form).getByText('Zadajte dvojpísmenový kód krajiny')).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('pri konflikte verzie ukáže upozornenie a načíta aktuálne údaje', async () => {
    mockServer();
    renderCard();
    await openEdit();
    card = { ...card, guests: [{ ...baseGuest, clientVersion: 5, email: 'iny@example.invalid' }] };
    await userEvent.click(await screen.findByRole('button', { name: 'Uložiť údaje' }));

    await waitFor(() => expect(puts).toHaveLength(1));
    expect(await screen.findByTestId('guest-edit-conflict')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('E-mail')).toHaveValue('iny@example.invalid'));
    expect(screen.getByTestId('guest-edit-conflict')).toBeInTheDocument();
  });

  it('bez RESERVATION_EDIT sa oprava neponúkne', async () => {
    mockServer();
    renderCard(receptionist);
    expect(await screen.findByTestId('guest-contact')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Upraviť údaje hosťa/ })).toBeNull();
  });
});
