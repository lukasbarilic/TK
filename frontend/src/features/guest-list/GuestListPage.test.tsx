import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { connection } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import { receptionist } from '../../shared/test/handlers';
import { renderWithProviders } from '../../shared/test/render';
import { server } from '../../shared/test/server';
import type { components } from '../../shared/api/schema';
import { GuestListPage } from './GuestListPage';
import { buildGuestCsv, type GuestLocations } from './guestListApi';

type Me = components['schemas']['MeView'];
const g = (o: object) => ({ reservationId: 1, guestName: 'Ukážková Mária', birthYear: 1958, building: 'Alfa', floor: '1', room: '102', bedNumber: 1, arrivalDate: '2026-11-17', departureDate: '2026-11-24', status: 'CHECKED_IN', doctorId: 7, doctorName: 'MUDr. Ukážkový', ...o });
const LIST: GuestLocations = {
  date: '2026-11-18',
  guests: [
    g({ reservationId: 1 }),
    g({ reservationId: 2, guestName: 'Demová Jana', building: 'Beta', room: '201', doctorId: 8, doctorName: 'MUDr. Vzorová' }),
    g({ reservationId: 3, guestName: 'Pokusný Viktor', room: '106', stayFrom: '2026-11-18', stayTo: '2026-11-24', movedOn: '2026-11-18' }),
  ],
};
const BUILDINGS = [{ id: 1, name: 'Alfa', active: true }, { id: 2, name: 'Beta', active: true }];
const withPerms = (...codes: string[]): Me => ({ ...receptionist, permissions: codes.map((code) => ({ code })) });
const DOCTOR = withPerms('GUEST_LOCATION_VIEW', 'GUEST_LOCATION_EXPORT');

function mockServer() {
  server.use(
    http.get('/api/receptions/:id/business-day', () => HttpResponse.json({ receptionId: 2, businessDate: '2026-11-18' })),
    http.get('/api/catalog/buildings', () => HttpResponse.json(BUILDINGS)),
    http.get('/api/reports/guest-locations', () => HttpResponse.json(LIST)),
  );
}
const renderPage = (me: Me) => renderWithProviders(<GuestListPage me={me} />);
const decode = (b: Uint8Array) => new TextDecoder('utf-8', { ignoreBOM: true }).decode(b);

beforeEach(async () => {
  await platform.vault.create('recepcna.demo', 'heslo-demo');
});
afterEach(async () => {
  connection.reset('online');
  vi.restoreAllMocks();
  await platform.vault.lock();
  await platform.vault.wipe('recepcna.demo');
});

describe('CSV', () => {
  it('BOM, oddeľovač ; a neutralizácia vzorcov', () => {
    const csv = decode(buildGuestCsv([{ reservationId: 1, guestName: 'Priezvisko; "Meno"', room: '=1+1', birthYear: 1950 }]));
    expect(csv.charCodeAt(0)).toBe(0xfeff);
    const [head, row] = csv.slice(1).split('\r\n');
    expect(head.startsWith('Priezvisko a meno;Rok narodenia;Budova')).toBe(true);
    expect(row.startsWith('"Priezvisko; ""Meno""";1950;;;\'=1+1;')).toBe(true);
    expect(head.split(';')).toHaveLength(14);
  });
});

describe('Zoznam hostí', () => {
  it('online: stĺpce, zvýraznený presun izby a filter budovy', async () => {
    mockServer();
    renderPage(DOCTOR);
    expect(await screen.findAllByTestId('guest-row')).toHaveLength(3);
    const moved = screen.getAllByTestId('guest-row').find((r) => r.dataset.moved === 'true');
    expect(moved).toHaveTextContent('Pokusný Viktor');
    expect(within(moved as HTMLElement).getByTestId('guest-moved')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Budova')).toHaveTextContent('Beta'));
    await userEvent.setup().selectOptions(screen.getByLabelText('Budova'), 'Beta');
    expect(screen.getAllByTestId('guest-row')).toHaveLength(1);
    expect(screen.getByTestId('guest-row')).toHaveTextContent('201 / 1');
  });

  it('lekár bez VIEW_ALL nemá výber lekára; s VIEW_ALL ho má a filtruje', async () => {
    mockServer();
    const { unmount } = renderPage(DOCTOR);
    await screen.findAllByTestId('guest-row');
    expect(screen.queryByLabelText('Lekár')).not.toBeInTheDocument();
    unmount();
    renderPage(withPerms('GUEST_LOCATION_VIEW', 'GUEST_LOCATION_VIEW_ALL'));
    await screen.findAllByTestId('guest-row');
    await userEvent.setup().selectOptions(screen.getByLabelText('Lekár'), '8');
    expect(screen.getAllByTestId('guest-row')).toHaveLength(1);
  });

  it('export a tlač sú bez oprávnenia GUEST_LOCATION_EXPORT skryté', async () => {
    mockServer();
    renderPage(withPerms('GUEST_LOCATION_VIEW'));
    await screen.findAllByTestId('guest-row');
    expect(screen.queryByRole('button', { name: 'Export CSV' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tlačiť' })).not.toBeInTheDocument();
  });

  it('online export stiahne format=csv a uloží ho cez Platform.files.save', async () => {
    mockServer();
    let url = '';
    server.use(
      http.get('/api/reports/guest-locations', ({ request }) => {
        url = request.url;
        return new URL(url).searchParams.get('format') === 'csv'
          ? new HttpResponse('﻿a;b\r\n', { headers: { 'Content-Type': 'text/csv' } })
          : HttpResponse.json(LIST);
      }),
    );
    const save = vi.spyOn(platform.files, 'save').mockResolvedValue(true);
    renderPage(DOCTOR);
    await screen.findAllByTestId('guest-row');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(save).toHaveBeenCalledOnce());
    expect(url).toContain('format=csv');
    expect(save.mock.calls[0][0]).toBe('zoznam-hosti-2026-11-18.csv');
    expect(await platform.vault.list(DS.journal)).toEqual([]);
  });

  it('offline: zobrazenie z trezora a export z lokálnych dát sa zapíše do denníka', async () => {
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: '2026-11-18' }, { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.guestList, '2026-11-18', LIST, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    const save = vi.spyOn(platform.files, 'save').mockResolvedValue(true);
    renderPage(DOCTOR);
    expect(await screen.findAllByTestId('guest-row')).toHaveLength(3);
    expect(screen.getByTestId('data-age')).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(save).toHaveBeenCalledOnce());
    const bytes = save.mock.calls[0][1];
    expect(decode(bytes).charCodeAt(0)).toBe(0xfeff);
    expect(decode(bytes)).toContain('Ukážková Mária;1958;Alfa');
    const keys = await platform.vault.list(DS.journal);
    expect(keys).toHaveLength(1);
    const entry = await platform.vault.get<{ operation: string; connection: string; clientIds: number[] }>(DS.journal, keys[0]);
    expect(entry?.value).toMatchObject({ operation: 'GUEST_LOCATION_EXPORT', connection: 'OFFLINE' });
    expect([...(entry?.value.clientIds ?? [])].sort()).toEqual([1, 2, 3]);
  });

  it('offline: deň, ktorý nie je uložený, ukáže hlášku', async () => {
    await platform.vault.put(DS.businessDay, '2', { receptionId: 2, businessDate: '2026-11-18' }, { syncedAt: '2026-11-18T08:00:00Z' });
    await platform.vault.put(DS.guestList, '2026-11-18', LIST, { syncedAt: '2026-11-18T08:00:00Z' });
    connection.reset('offline');
    renderPage(DOCTOR);
    await screen.findAllByTestId('guest-row');
    const input = screen.getByLabelText('Deň');
    fireEvent.change(input, { target: { value: '2026-11-10' } });
    expect(await screen.findByTestId('guest-not-stored')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeDisabled();
  });

  it('tlač: hlavička s dňom, lekárom a časom tlače', async () => {
    mockServer();
    const print = vi.spyOn(window, 'print').mockImplementation(() => {});
    renderPage(DOCTOR);
    await screen.findAllByTestId('guest-row');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Tlačiť' }));
    expect(print).toHaveBeenCalledOnce();
    const head = screen.getByTestId('guest-print-head');
    expect(head).toHaveTextContent('18. 11. 2026');
    expect(head).toHaveTextContent('Lekár: Recepčná Ukážková');
    expect(head).toHaveTextContent(/Vytlačené: \S+/);
  });
});
