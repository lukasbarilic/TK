import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { connection } from '../../shared/offline';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import { AuditPage } from './AuditPage';

const ev = (id: number) => ({ id, eventType: 'WRITE', occurredAt: '2030-01-05T10:00:00Z', username: 'recepcna.demo', operation: 'STAY_MOVE', targetType: 'Stay', targetId: 7, outcome: 'SUCCESS' });

function Where() {
  const l = useLocation();
  return <div data-testid="where">{l.search}</div>;
}
const show = (url = '/audit') =>
  renderWithProviders(
    <MemoryRouter initialEntries={[url]}>
      <AuditPage />
      <Where />
    </MemoryRouter>,
  );

afterEach(() => connection.reset('online'));

describe('AuditPage', () => {
  it('filtre idú na server aj do URL, stránkovanie na serveri', async () => {
    const seen: URLSearchParams[] = [];
    server.use(
      http.get('/api/audit/events', ({ request }) => {
        const q = new URL(request.url).searchParams;
        seen.push(q);
        return HttpResponse.json({ content: [ev(Number(q.get('page') ?? 0) + 1)], page: Number(q.get('page')), size: 20, totalElements: 45 });
      }),
    );
    show();
    expect(await screen.findAllByTestId('audit-row')).toHaveLength(1);
    await userEvent.selectOptions(screen.getByLabelText('Typ'), 'WRITE');
    await userEvent.type(screen.getByLabelText('ID entity'), '7');
    await userEvent.type(screen.getByLabelText('Operácia'), 'STAY_MOVE');
    await userEvent.click(screen.getByRole('button', { name: 'Hľadať' }));
    await screen.findByText('Strana 1 z 3');
    const last = () => seen[seen.length - 1];
    expect(last().get('type')).toBe('WRITE');
    expect(last().get('entityId')).toBe('7');
    expect(last().get('operation')).toBe('STAY_MOVE');
    expect(screen.getByTestId('where')).toHaveTextContent('type=WRITE');
    await userEvent.click(screen.getByRole('button', { name: 'Ďalej' }));
    await screen.findByText('Strana 2 z 3');
    expect(last().get('page')).toBe('1');
    expect(last().get('entityId')).toBe('7');
  });

  it('filtre sa načítajú z URL', async () => {
    let q: URLSearchParams | undefined;
    server.use(http.get('/api/audit/events', ({ request }) => { q = new URL(request.url).searchParams; return HttpResponse.json({ content: [], totalElements: 0 }); }));
    show('/audit?userId=5&entityType=Stay');
    expect(await screen.findByText('Žiadne záznamy.')).toBeInTheDocument();
    expect(q?.get('userId')).toBe('5');
    expect(q?.get('entityType')).toBe('Stay');
    expect(screen.getByLabelText('Entita')).toHaveValue('Stay');
  });

  it('detail zobrazí porovnanie pred a po', async () => {
    server.use(
      http.get('/api/audit/events', () => HttpResponse.json({ content: [ev(3)], totalElements: 1 })),
      http.get('/api/audit/events/3', () =>
        HttpResponse.json({
          event: ev(3),
          changes: [{ id: 1, entityType: 'Stay', entityId: 7, changeType: 'UPDATE', beforeState: { bedId: 1, status: 'ACTIVE' }, afterState: { bedId: 2, status: 'ACTIVE' } }],
        }),
      ),
    );
    show();
    await userEvent.click(await screen.findByRole('button', { name: 'Detail 3' }));
    const diff = await screen.findByTestId('json-diff');
    expect(within(diff).getAllByTestId('diff-changed')).toHaveLength(1);
    expect(within(diff).getByTestId('diff-changed')).toHaveTextContent('bedId12');
    expect(within(diff).getAllByTestId('diff-same')).toHaveLength(1);
    expect(screen.getByTestId('where')).toHaveTextContent('event=3');
  });

  it('prístupy ku klientovi označia OFFLINE a CACHED záznamy', async () => {
    server.use(
      http.get('/api/audit/clients/9/access', () =>
        HttpResponse.json({
          content: [
            { eventId: 1, occurredAt: '2030-01-05T10:00:00Z', username: 'recepcna.demo', operation: 'CLIENT_READ', dataScope: 'CARD', source: 'ONLINE' },
            { eventId: 2, occurredAt: '2030-01-05T11:00:00Z', username: 'lekar.demo', operation: 'CLIENT_READ', dataScope: 'CARD', source: 'OFFLINE' },
            { eventId: 3, occurredAt: '2030-01-05T12:00:00Z', username: 'lekar.demo', operation: 'CLIENT_READ', dataScope: 'LIST', source: 'CACHED' },
          ],
          totalElements: 3,
        }),
      ),
    );
    show('/audit?view=access');
    await userEvent.type(screen.getByLabelText('ID klienta'), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Hľadať' }));
    expect(await screen.findAllByTestId('access-row')).toHaveLength(3);
    expect(screen.getAllByTestId('access-source').map((b) => b.textContent)).toEqual(['OFFLINE', 'CACHED']);
  });

  it('offline: upozornenie a žiadne volanie API', async () => {
    let called = false;
    server.use(http.get('/api/audit/events', () => { called = true; return HttpResponse.json({ content: [] }); }));
    connection.reset('offline');
    show();
    expect(await screen.findByTestId('audit-offline')).toBeInTheDocument();
    expect(called).toBe(false);
  });
});
