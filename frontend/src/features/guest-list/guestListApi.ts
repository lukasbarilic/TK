import { apiBytes, apiCall } from '../../shared/api/client';
import { offlineQuery, type JournalSpec } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import type { components } from '../../shared/api/schema';

/** Riadok zoznamu hostí (B05, HLD-ubytovanie kap. 8.5); `movedOn` = karta v daný deň zmenila izbu. */
export interface GuestLocation {
  reservationId: number;
  guestName?: string;
  birthYear?: number | null;
  building?: string | null;
  floor?: string | null;
  room?: string | null;
  bedNumber?: number | null;
  bedType?: string | null;
  arrivalDate?: string | null;
  departureDate?: string | null;
  status?: string | null;
  doctorId?: number | null;
  doctorName?: string | null;
  stayFrom?: string | null;
  stayTo?: string | null;
  movedOn?: string | null;
}
export interface GuestLocations {
  date: string;
  guests: GuestLocation[];
}

/** Denník čítaní (DLD kap. 7): klienti = ID kariet hostí v zozname (rovnako ako pri exporte). */
const guestListJournal: JournalSpec<GuestLocations> = {
  operation: 'GUEST_LOCATION_VIEW',
  dataScope: 'NAME',
  clientIds: (l) => [...new Set(l.guests.map((g) => g.reservationId))],
};

/** Zoznam hostí na deň: online zo servera, offline z trezora (`guest-list/<date>`). Filtre sa uplatňujú lokálne. */
export const loadGuestList = (date: string) =>
  offlineQuery<GuestLocations>(DS.guestList, date, async (etag) => {
    const res = await apiCall('get', '/api/reports/guest-locations', {
      query: { date },
      headers: etag ? { 'If-None-Match': etag } : {},
    });
    return res.status === 304 ? { status: 304 } : { status: 200, data: res.data as unknown as GuestLocations, etag: res.headers.etag };
  }, undefined, guestListJournal);

type BuildingView = components['schemas']['BuildingView'];

/** Budovy z číselníka (offline z trezora). */
export const loadBuildings = () =>
  offlineQuery<BuildingView[]>(DS.catalog, 'buildings', async (etag) => {
    const res = await apiCall('get', '/api/catalog/buildings', { headers: etag ? { 'If-None-Match': etag } : {} });
    return res.status === 304 ? { status: 304 } : { status: 200, data: res.data, etag: res.headers.etag };
  });

type DayView = components['schemas']['DayView'];

/** Obchodný deň recepcie (predvolený deň zoznamu). */
export const loadBusinessDay = (receptionId: number) =>
  offlineQuery<DayView>(DS.businessDay, String(receptionId), async () => ({
    status: 200,
    data: (await apiCall('get', '/api/receptions/{id}/business-day', { path: { id: receptionId } })).data,
  }));

/** CSV zo servera (`format=csv`); parametre musia zodpovedať lokálnemu filtru. */
export const downloadGuestCsv = (date: string, buildingId?: number, doctorId?: number) =>
  apiBytes('/api/reports/guest-locations', { date, format: 'csv', buildingId, doctorId });

const HEADER = [
  'Priezvisko a meno', 'Rok narodenia', 'Budova', 'Poschodie', 'Izba', 'Lôžko', 'Typ lôžka',
  'Príchod', 'Odchod', 'Stav karty', 'Ošetrujúci lekár', 'Pobyt od', 'Pobyt do', 'Presun dňa',
];

function cell(v: string | number | null | undefined): string {
  let s = v == null ? '' : String(v);
  if (s === '') return '';
  if ('=+-@'.includes(s[0])) s = `'${s}`;
  return /[;"\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

/** CSV v rovnakom formáte ako server: UTF-8 s BOM, oddeľovač `;`, riadky `\r\n`. */
export function buildGuestCsv(guests: GuestLocation[]): Uint8Array {
  const lines = [HEADER.join(';')];
  for (const g of guests) {
    lines.push(
      [
        g.guestName, g.birthYear, g.building, g.floor, g.room, g.bedNumber, g.bedType, g.arrivalDate,
        g.departureDate, g.status, g.doctorName, g.stayFrom, g.stayTo, g.movedOn,
      ]
        .map(cell)
        .join(';'),
    );
  }
  return new TextEncoder().encode(`${String.fromCharCode(0xfeff)}${lines.join('\r\n')}\r\n`);
}

export const saveCsv = (name: string, bytes: Uint8Array) =>
  platform.files.save(name, bytes, [{ name: 'CSV', extensions: ['csv'] }]);

/** Zápis exportu z lokálnej kópie do denníka čítaní (DLD kap. 7); odosiela ho F15. Chyby trezora sa ignorujú. */
export async function journalOfflineExport(username: string, clientIds: number[]): Promise<void> {
  try {
    const uid = crypto.randomUUID();
    const now = new Date().toISOString();
    await platform.vault.put(
      DS.journal,
      uid,
      {
        uid,
        occurredAt: now,
        username,
        deviceId: await platform.config.deviceId(),
        operation: 'GUEST_LOCATION_EXPORT',
        dataScope: 'NAME',
        clientIds,
        connection: 'OFFLINE',
      },
      { syncedAt: now },
    );
  } catch {
    // zamknutý trezor: denník sa nezapíše
  }
}

/** Filtrovanie zoznamu (budova podľa názvu, lekár podľa ID) a triedenie podľa budovy, izby, mena. */
export function filterGuests(all: GuestLocation[], building: string, doctorId: string): GuestLocation[] {
  const cmp = (a?: string | null, b?: string | null) => (a ?? '').localeCompare(b ?? '', 'sk', { numeric: true, sensitivity: 'base' });
  return all
    .filter((g) => (!building || g.building === building) && (!doctorId || String(g.doctorId) === doctorId))
    .sort((a, b) => cmp(a.building, b.building) || cmp(a.room, b.room) || (a.bedNumber ?? 0) - (b.bedNumber ?? 0) || cmp(a.guestName, b.guestName));
}
