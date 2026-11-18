import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';

export type AvailableRoom = components['schemas']['AvailableRoomView'];
export type StayView = components['schemas']['StayView'];
export type CardListItem = components['schemas']['ListItem'];

/** Telo `POST /api/reservations/{id}/stays` (`from`/`to` = interval nocí `[from, to)`). */
export interface CreateStayBody {
  bedId: number;
  from: string;
  to: string;
  adjustReservationDates?: boolean;
  confirm?: string[];
}

export interface AvailableParams {
  from: string;
  to: string;
  /** Kategória karty; bez nej sa hľadá vo „všetkých kategóriách“ (vyžaduje `STAY_OTHER_CATEGORY`). */
  categoryId?: number;
  allCategories: boolean;
}

/** Voľné izby a lôžka pre interval (UC1). */
export const loadAvailableRooms = async (p: AvailableParams): Promise<AvailableRoom[]> =>
  (
    await apiCall('get', '/api/rooms/available', {
      query: {
        from: p.from,
        to: p.to,
        categoryId: p.allCategories ? undefined : p.categoryId,
        allCategories: p.allCategories || undefined,
      },
    })
  ).data;

export interface CreatedStay {
  stay: StayView;
  /** `X-Audit-Event-Id` – vlastná živá udalosť s týmto ID sa nespracuje druhýkrát. */
  auditEventId?: number;
}

/**
 * Ubytovanie karty na lôžko. Telo v `schema.d.ts` je kvôli kolízii názvov (`CreateRequest`) pomenované podľa
 * rezervácie, preto ho tu pretypujeme na skutočný tvar `StayController.CreateRequest`.
 */
export async function createStay(reservationId: number, body: CreateStayBody): Promise<CreatedStay> {
  const res = await apiCall('post', '/api/reservations/{id}/stays', {
    path: { id: reservationId },
    body: body as unknown as components['schemas']['CreateRequest'],
  });
  const id = Number(res.headers['x-audit-event-id']);
  return { stay: res.data, auditEventId: Number.isFinite(id) && res.headers['x-audit-event-id'] ? id : undefined };
}

/** Karty v stave `RESERVED` bez pobytu (UC2): hľadanie na serveri, pobyty sa overia po kartách. */
export async function searchCardsWithoutStay(q: string): Promise<CardListItem[]> {
  const res = (await apiCall('get', '/api/reservations', { query: { q: q.trim() || undefined, status: 'RESERVED', page: 0, size: 20 } })).data;
  const cards = (res.content ?? []).filter((c) => c.id !== undefined && c.status === 'RESERVED');
  const stays = await Promise.all(
    cards.map((c) => apiCall('get', '/api/reservations/{id}/stays', { path: { id: c.id as number } }).then((r) => r.data.length)),
  );
  return cards.filter((_, i) => stays[i] === 0);
}
