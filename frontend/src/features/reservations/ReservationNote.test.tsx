import type { components } from '../../shared/api/schema';

type Me = components['schemas']['MeView'];
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { ReservationDetailPage } from './ReservationDetailPage';

const editor: Me = { ...receptionist, permissions: [...(receptionist.permissions ?? []), { code: 'RESERVATION_EDIT', scopes: [{ receptionId: 2 }] }] };

const baseCard = {
  id: 912, reservationNumber: 'R-2026-0912', status: 'RESERVED' as const,
  arrivalDate: '2026-11-19', departureDate: '2026-11-22', version: 3,
  note: 'Alergia na orechy (fiktívna).', noteInPlan: true,
  comments: [
    { id: 1, authorId: 7, createdAt: '2026-11-17T08:00:00Z', text: 'Starší komentár' },
    { id: 2, authorId: 8, createdAt: '2026-11-18T09:30:00Z', text: 'Novší komentár' },
  ],
  guests: [{ id: 1, clientId: 140, firstName: 'Žofia', lastName: 'Ľudová', primaryGuest: true }],
};

let card = baseCard;
let patches: unknown[] = [];
let posts: unknown[] = [];

function mockServer() {
  patches = [];
  posts = [];
  server.use(
    http.get('/api/reservations/:id', () => HttpResponse.json(card)),
    http.get('/api/reservations/:id/stays', () => HttpResponse.json([])),
    http.patch('/api/reservations/:id', async ({ request }) => {
      const body = (await request.json()) as { version: number; note?: string; noteInPlan?: boolean };
      patches.push(body);
      if (body.version !== card.version) return HttpResponse.json({ status: 409, code: 'VERSION_CONFLICT' }, { status: 409 });
      card = { ...card, note: body.note ?? card.note, noteInPlan: body.noteInPlan ?? card.noteInPlan, version: card.version + 1 };
      return HttpResponse.json(card);
    }),
    http.post('/api/reservations/:id/comments', async ({ request }) => {
      const body = (await request.json()) as { text: string };
      posts.push(body);
      const c = { id: 3, authorId: 1, createdAt: '2026-11-19T10:00:00Z', text: body.text };
      card = { ...card, comments: [...card.comments, c] };
      return HttpResponse.json(c, { status: 201 });
    }),
    http.post('/api/reservations/:id/comments/:commentId/hide', ({ params }) => {
      posts.push({ hide: Number(params.commentId) });
      card = { ...card, comments: card.comments.filter((c) => c.id !== Number(params.commentId)) };
      return new HttpResponse(null, { status: 204 });
    }),
  );
}

const renderCard = (me: Me = editor) =>
  renderWithProviders(
    <MemoryRouter>
      <ReservationDetailPage id={912} me={me} canSeeContacts />
    </MemoryRouter>,
  );

beforeEach(async () => {
  card = baseCard;
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('Poznámka karty', () => {
  it('zobrazí poznámku a uloží úpravu s verziou a voľbou ikony v pláne', async () => {
    mockServer();
    renderCard();
    const panel = await screen.findByTestId('note-panel');
    expect(within(panel).getByTestId('note-text')).toHaveTextContent('Alergia na orechy');
    await userEvent.click(within(panel).getByRole('button', { name: 'Upraviť poznámku' }));
    const area = within(panel).getByLabelText('Text poznámky');
    await userEvent.clear(area);
    await userEvent.type(area, 'Nová poznámka');
    await userEvent.click(within(panel).getByLabelText('Zobraziť ikonu v pláne'));
    await userEvent.click(within(panel).getByRole('button', { name: 'Uložiť poznámku' }));
    await waitFor(() => expect(patches).toEqual([{ version: 3, note: 'Nová poznámka', noteInPlan: false }]));
    await waitFor(() => expect(screen.getByTestId('note-text')).toHaveTextContent('Nová poznámka'));
  });

  it('konflikt verzie ukáže upozornenie a obnoví kartu', async () => {
    mockServer();
    renderCard();
    await userEvent.click(await screen.findByRole('button', { name: 'Upraviť poznámku' }));
    card = { ...card, version: 4, note: 'Zmenené niekým iným' };
    await userEvent.click(screen.getByRole('button', { name: 'Uložiť poznámku' }));
    expect(await screen.findByTestId('note-conflict')).toBeInTheDocument();
    await waitFor(() => expect(patches).toHaveLength(1));
  });

  it('bez RESERVATION_EDIT nie je úprava ani pridanie komentára', async () => {
    mockServer();
    renderCard(receptionist);
    expect(await screen.findByTestId('note-text')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Upraviť poznámku' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Pridať komentár' })).toBeNull();
    expect(screen.getAllByTestId('comment')).toHaveLength(2);
  });
});

describe('Komentáre karty', () => {
  it('zoradí najnovšie hore, pridá komentár a skrytie vyžaduje potvrdenie', async () => {
    mockServer();
    renderCard();
    const rows = await screen.findAllByTestId('comment');
    expect(rows[0]).toHaveTextContent('Novší komentár');
    expect(rows[1]).toHaveTextContent('Starší komentár');

    await userEvent.type(screen.getByLabelText('Nový komentár'), 'Pridaný komentár');
    await userEvent.click(screen.getByRole('button', { name: 'Pridať komentár' }));
    await waitFor(() => expect(posts).toEqual([{ text: 'Pridaný komentár' }]));
    expect(await screen.findByText('Pridaný komentár')).toBeInTheDocument();

    await userEvent.click(screen.getAllByRole('button', { name: 'Skryť' })[0]);
    expect(posts).toHaveLength(1);
    await userEvent.click(await screen.findByRole('button', { name: 'Skryť komentár' }));
    await waitFor(() => expect(posts[1]).toEqual({ hide: 3 }));
    await waitFor(() => expect(screen.queryByText('Pridaný komentár')).toBeNull());
  });
});

describe('Poznámka a komentáre offline', () => {
  it('zobrazí sa z trezora, zápis je neaktívny a čítanie ide do denníka', async () => {
    await platform.vault.put(DS.reservation, '912', baseCard, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderCard();
    expect(await screen.findByTestId('note-text')).toHaveTextContent('Alergia na orechy');
    expect(screen.getAllByTestId('comment')[0]).toHaveTextContent('Novší komentár');
    expect(screen.getByRole('button', { name: 'Upraviť poznámku' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Pridať komentár' })).toBeDisabled();
    await waitFor(async () => expect((await platform.vault.list(DS.journal)).length).toBeGreaterThan(0));
    const key = (await platform.vault.list(DS.journal))[0];
    const entry = await platform.vault.get<{ operation: string; dataScope: string; clientIds: number[] }>(DS.journal, key);
    expect(entry?.value).toMatchObject({ operation: 'RESERVATION_VIEW', dataScope: 'CONTACT', clientIds: [140] });
  });
});
