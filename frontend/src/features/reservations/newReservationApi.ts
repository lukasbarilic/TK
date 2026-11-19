import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';

export type ClientItem = components['schemas']['ClientItem'];
export type CategoryOption = components['schemas']['RoomCategoryView'];
export type PayerOption = components['schemas']['PayerView'];
export type AvailableRoomView = components['schemas']['AvailableRoomView'];
export type CreateWithStayBody = components['schemas']['CreateWithStayRequest'];
export type GuestBody = components['schemas']['GuestRequest'];
/** Telo `POST /api/reservations`: to isté ako `with-stay` bez lôžka (`schema.d.ts` pri `CreateRequest` zaostáva za serverom). */
export type CreateCardBody = Omit<CreateWithStayBody, 'bedId' | 'extraBed' | 'confirm'>;

export interface CreatedCard {
  reservationId: number;
  /** `X-Audit-Event-Id` – vlastná živá udalosť s týmto ID sa nespracuje druhýkrát. */
  auditEventId?: number;
}

const auditId = (headers: Record<string, string>): number | undefined => {
  const raw = headers['x-audit-event-id'];
  const n = Number(raw);
  return raw && Number.isFinite(n) ? n : undefined;
};

/** Vyhľadanie existujúceho klienta (`RESERVATION_EDIT`); bez kontaktov. */
export const searchClients = async (q: string): Promise<ClientItem[]> =>
  (await apiCall('get', '/api/clients', { query: { q: q.trim(), limit: 10 } })).data;

export const loadCategories = async (): Promise<CategoryOption[]> => (await apiCall('get', '/api/catalog/room-categories')).data;
export const loadPayers = async (): Promise<PayerOption[]> => (await apiCall('get', '/api/catalog/payers')).data;

/** Voľné izby a lôžka kategórie v termíne (kontrola v súhrne a výber iného lôžka). */
export const loadAvailable = async (from: string, to: string, categoryId: number): Promise<AvailableRoomView[]> =>
  (await apiCall('get', '/api/rooms/available', { query: { from, to, categoryId } })).data;

/** „Uložiť len kartu“: `POST /api/reservations`. */
export async function createCard(body: CreateCardBody): Promise<CreatedCard> {
  const res = await apiCall('post', '/api/reservations', { body: body as unknown as components['schemas']['CreateRequest'] });
  return { reservationId: res.data.id as number, auditEventId: auditId(res.headers) };
}

/** „Uložiť a prideliť lôžko“: `POST /api/reservations/with-stay` (jedna transakcia – pri chybe sa nezaloží ani karta). */
export async function createCardWithStay(body: CreateWithStayBody): Promise<CreatedCard & { roomId?: number }> {
  const res = await apiCall('post', '/api/reservations/with-stay', { body });
  return { reservationId: res.data.reservation.id as number, roomId: res.data.stay.roomId, auditEventId: auditId(res.headers) };
}
