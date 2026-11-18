import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { connection } from '../shared/offline';
import { DS } from '../shared/offline/datasets';
import { platform } from '../shared/platform';
import { receptionist } from '../shared/test/handlers';
import { renderWithProviders } from '../shared/test/render';
import { server } from '../shared/test/server';
import { PlanPage } from '../features/plan';
import { BUSINESS_DATE, samplePlan, stayBar } from '../features/plan/testData';
import { ReservationDetailPage } from '../features/reservations';
import { CheckInDialog } from '../features/stays';

const card = {
  id: 912, reservationNumber: 'R-2026-0912', status: 'RESERVED' as const,
  arrivalDate: '2026-11-22', departureDate: '2026-11-25', version: 3, bookedCategoryId: 2,
  guests: [{ id: 1, firstName: 'Anna', lastName: 'Testovská', primaryGuest: true }],
};
const createdStay = {
  id: 900, reservationId: 912, version: 0, buildingId: 1, buildingName: 'ALF', roomId: 2, roomNumber: '102',
  bedId: 21, bedNumber: 1, bedType: 'REGULAR', dateFrom: '2026-11-22', dateTo: '2026-11-25', priceCategoryId: 2, locked: false,
};
const availableRooms = [
  { roomId: 2, roomNumber: '102', buildingId: 1, buildingName: 'ALF', receptionId: 2, categoryId: 2, categoryName: 'K2', matchesCategory: true,
    freeBeds: [{ id: 21, bedNumber: 1, type: 'REGULAR' }, { id: 22, bedNumber: 2, type: 'REGULAR' }] },
];

let posts: { id: string; body: { bedId: number; from: string; to: string; confirm?: string[] } }[] = [];
let availableQueries: URL[] = [];
let planWithStay = false;

function mockServer(postResponse: (n: number, body: { confirm?: string[] }) => Response = () => HttpResponse.json(createdStay, { status: 201 })) {
  posts = [];
  availableQueries = [];
  planWithStay = false;
  server.use(
    http.get('/api/catalog/receptions', () => HttpResponse.json([{ id: 2, code: 'R2', name: 'Recepcia B', active: true }])),
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: BUSINESS_DATE })),
    http.get('/api/client-config', () => HttpResponse.json({ offline: { planDaysBack: 1, planDaysAhead: 14 } })),
    http.get('/api/me/preferences/:key', () => HttpResponse.json({}, { status: 404 })),
    http.get('/api/accommodation-plan', ({ request }) => {
      const ids = new URL(request.url).searchParams.getAll('roomIds').map(Number);
      const p = samplePlan();
      if (planWithStay) p.rooms?.find((r) => r.roomId === 2)?.bars?.push(stayBar(900, 21, '2026-11-22', '2026-11-25', 'Testovská A.', 'RESERVED'));
      return HttpResponse.json(ids.length ? { ...p, rooms: p.rooms?.filter((r) => ids.includes(r.roomId as number)) } : p);
    }),
    http.get('/api/rooms/available', ({ request }) => {
      availableQueries.push(new URL(request.url));
      return HttpResponse.json(availableRooms);
    }),
    http.get('/api/reservations/:id/stays', ({ params }) =>
      HttpResponse.json(params.id === '913' ? [{ ...createdStay, id: 1, reservationId: 913 }] : []),
    ),
    http.get('/api/reservations/:id', () => HttpResponse.json(card)),
    http.get('/api/reservations', () =>
      HttpResponse.json({
        content: [
          { id: 912, reservationNumber: 'R-2026-0912', guestLastName: 'Testovská', guestFirstName: 'Anna', arrivalDate: '2026-11-22', departureDate: '2026-11-25', status: 'RESERVED' },
          { id: 913, reservationNumber: 'R-2026-0913', guestLastName: 'Mala', guestFirstName: 'Eva', arrivalDate: '2026-11-22', departureDate: '2026-11-25', status: 'RESERVED' },
        ],
        page: 0, size: 20, totalElements: 2,
      }),
    ),
    http.post('/api/reservations/:id/stays', async ({ request, params }) => {
      const body = (await request.json()) as { bedId: number; from: string; to: string; confirm?: string[] };
      posts.push({ id: String(params.id), body });
      const res = postResponse(posts.length, body);
      if (res.status === 201) planWithStay = true;
      return res;
    }),
  );
}

const problem = (status: number, code: string, extra: object = {}) =>
  HttpResponse.json({ status, code, title: code, ...extra }, { status });

beforeEach(async () => {
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

const renderCard = () =>
  renderWithProviders(
    <MemoryRouter>
      <ReservationDetailPage
        id={912}
        canSeeContacts
        renderCheckIn={(d, onClose) => (
          <CheckInDialog me={receptionist} onClose={onClose}
            card={{ id: 912, reservationNumber: d.reservationNumber, arrivalDate: d.arrivalDate, departureDate: d.departureDate, bookedCategoryId: d.bookedCategoryId }} />
        )}
      />
    </MemoryRouter>,
  );

const renderPlan = () =>
  renderWithProviders(
    <PlanPage me={receptionist} renderCheckIn={(target, onClose) => <CheckInDialog me={receptionist} onClose={onClose} target={target} />} />,
  );

const openFromCard = async () => {
  renderCard();
  await userEvent.click(await screen.findByRole('button', { name: 'Ubytovať' }));
  return screen.findByTestId('checkin-dialog');
};

describe('Ubytovanie z karty', () => {
  it('načíta voľné izby podľa kategórie karty a po výbere lôžka ubytuje', async () => {
    mockServer();
    const dialog = await openFromCard();
    await within(dialog).findByText(/ALF 102/);
    expect(availableQueries[0].searchParams.get('categoryId')).toBe('2');
    expect(availableQueries[0].searchParams.get('from')).toBe('2026-11-22');
    expect(availableQueries[0].searchParams.get('to')).toBe('2026-11-25');
    expect(availableQueries[0].searchParams.get('allCategories')).toBeNull();

    await userEvent.click(within(dialog).getByRole('radio', { name: 'Lôžko 2' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Ubytovať' }));
    await waitFor(() => expect(screen.queryByTestId('checkin-dialog')).toBeNull());
    expect(posts).toEqual([{ id: '912', body: { bedId: 22, from: '2026-11-22', to: '2026-11-25' } }]);
  });

  it('„Všetky kategórie“ pošle allCategories bez categoryId', async () => {
    mockServer();
    const dialog = await openFromCard();
    await within(dialog).findByText(/ALF 102/);
    await userEvent.click(within(dialog).getByRole('switch', { name: 'Všetky kategórie' }));
    await waitFor(() => expect(availableQueries).toHaveLength(2));
    expect(availableQueries[1].searchParams.get('allCategories')).toBe('true');
    expect(availableQueries[1].searchParams.get('categoryId')).toBeNull();
  });

  it('zobrazí limit nocí a nepustí dlhší pobyt', async () => {
    mockServer();
    const dialog = await openFromCard();
    await within(dialog).findByText(/ALF 102/);
    expect(within(dialog).getByTestId('checkin-limit')).toHaveTextContent('Najviac 14 nocí');
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Lôžko 1' }));
    const submit = within(dialog).getByRole('button', { name: 'Ubytovať' });
    expect(submit).toBeEnabled();
    fireEvent.change(within(dialog).getByLabelText('Do (odchod)'), { target: { value: '2026-12-10' } });
    expect(await within(dialog).findByTestId('checkin-too-long')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Ubytovať' })).toBeDisabled();
    expect(posts).toHaveLength(0);
  });

  it('upozornenie 409 confirmable sa potvrdí a požiadavka sa pošle znova s confirm', async () => {
    mockServer((n, body) =>
      n === 1 && !body.confirm
        ? problem(409, 'CONFIRMATION_REQUIRED', { confirmable: true, warnings: [{ code: 'RELEASES_HELD_BED' }].map((w) => w.code) })
        : HttpResponse.json(createdStay, { status: 201 }),
    );
    const dialog = await openFromCard();
    await within(dialog).findByText(/ALF 102/);
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Lôžko 1' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Ubytovať' }));

    const warnings = await screen.findByTestId('confirm-warnings');
    expect(warnings).toHaveTextContent('Ubytovaním sa uvoľní lôžko držané pre iný pobyt.');
    await userEvent.click(screen.getByRole('button', { name: 'Pokračovať' }));
    await waitFor(() => expect(screen.queryByTestId('checkin-dialog')).toBeNull());
    expect(posts.map((p) => p.body.confirm)).toEqual([undefined, ['RELEASES_HELD_BED']]);
  });

  it('BED_OCCUPIED: zobrazí chybu, obnoví voľné izby a nezopakuje požiadavku', async () => {
    mockServer(() => problem(409, 'BED_OCCUPIED'));
    const dialog = await openFromCard();
    await within(dialog).findByText(/ALF 102/);
    await userEvent.click(within(dialog).getByRole('radio', { name: 'Lôžko 1' }));
    await userEvent.click(within(dialog).getByRole('button', { name: 'Ubytovať' }));
    expect(await within(dialog).findByTestId('checkin-error')).toHaveTextContent('Lôžko je v tomto termíne už obsadené.');
    await waitFor(() => expect(availableQueries.length).toBeGreaterThan(1));
    expect(posts).toHaveLength(1);
    expect(screen.queryByTestId('confirm-warnings')).toBeNull();
  });

  it('bez spojenia je tlačidlo „Ubytovať“ neaktívne', async () => {
    mockServer();
    await platform.vault.put(DS.reservation, '912', card, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderCard();
    expect(await screen.findByRole('button', { name: 'Ubytovať' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Ubytovať' }).closest('[data-testid="write-disabled"]')).not.toBeNull();
  });
});

describe('Ubytovanie z plánu', () => {
  it('klik na voľné lôžko a deň → hľadanie karty bez pobytu → potvrdenie → plán sa obnoví', async () => {
    mockServer();
    renderPlan();
    await screen.findAllByTestId('plan-bar');
    const row = document.querySelector('[data-bed-id="21"]') as HTMLElement;
    // 22. 11. = stĺpec 5 (okno od 17. 11., deň 36 px)
    fireEvent.click(within(row).getByTestId('plan-bed-cells'), { clientX: 5 * 36 + 5 });

    const dialog = await screen.findByTestId('checkin-dialog');
    expect(within(dialog).getByTestId('checkin-target')).toHaveTextContent('ALF 102, Lôžko 1, od 2026-11-22');
    // karta 913 už pobyt má → ponúkne sa len 912
    const cards = await within(dialog).findAllByTestId('checkin-card');
    expect(cards).toHaveLength(1);
    expect(cards[0]).toHaveTextContent('R-2026-0912');
    await userEvent.click(within(cards[0]).getByRole('button', { name: 'Vybrať' }));

    expect(within(dialog).getByTestId('checkin-nights')).toHaveTextContent('3 noci');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Ubytovať' }));
    await waitFor(() => expect(screen.queryByTestId('checkin-dialog')).toBeNull());
    expect(posts).toEqual([{ id: '912', body: { bedId: 21, from: '2026-11-22', to: '2026-11-25' } }]);

    // dotknutá izba sa v pláne aktualizovala z odpovede servera
    await waitFor(() => {
      const added = screen.getAllByTestId('plan-bar').find((b) => b.getAttribute('data-bar-id') === 'stay:900');
      expect(added).toBeDefined();
    });
  });

  it('klik na obsadený deň dialóg neotvorí; bez spojenia sa dialóg neponúka', async () => {
    mockServer();
    renderPlan();
    await screen.findAllByTestId('plan-bar');
    const occupied = document.querySelector('[data-bed-id="11"]') as HTMLElement;
    fireEvent.click(within(occupied).getByTestId('plan-bed-cells'), { clientX: 3 * 36 }); // 20. 11. – obsadené pobytom 812
    expect(screen.queryByTestId('checkin-dialog')).toBeNull();
  });
});
