import { apiCall } from '../../shared/api/client';
import { isNetError } from '../../shared/api/errors';
import type { components } from '../../shared/api/schema';
import { connection, offlineQuery, reservationViewSpec } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';
import { addDays } from '../../shared/time';

export type ReservationDetail = components['schemas']['Detail'];
export type ReservationListItem = components['schemas']['ListItem'];
export type ReservationStay = components['schemas']['StayView'];
export type ReservationUpdate = components['schemas']['UpdateRequest'];
export type ReservationComment = components['schemas']['CommentView'];
export type ReservationGuest = components['schemas']['GuestView'];
export type ClientUpdate = components['schemas']['ClientUpdateRequest'];
export type ClientView = components['schemas']['ClientView'];
export type ReservationStatus = NonNullable<ReservationListItem['status']>;

export const PAGE_SIZE = 20;

export interface SearchParams {
  q: string;
  status?: ReservationStatus;
  /** Pobyt od (YYYY-MM-DD): karty, ktoré v tento deň alebo neskôr ešte trvajú (odchod je neskôr). */
  from?: string;
  /** Pobyt do (YYYY-MM-DD, vrátane): karty s príchodom najneskôr v tento deň. */
  to?: string;
  page: number;
}

/** Karta sa prekrýva s obdobím [from, to] (oba dni vrátane; noc odchodu sa už nepočíta). */
export const overlaps = (arrival: string | undefined, departure: string | undefined, from?: string, to?: string): boolean =>
  (!to || (arrival !== undefined && arrival <= to)) && (!from || (departure !== undefined && departure > from));

export interface SearchResult {
  /** `local` = hľadanie len v kartách uložených v trezore na tomto PC. */
  mode: 'online' | 'local';
  items: ReservationListItem[];
  page: number;
  totalElements: number;
}

/** Bez diakritiky a bez ohľadu na veľkosť písmen („Žofia“ ≈ „zofia“). */
export const normalize = (s: string | undefined): string =>
  (s ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim();

function toListItem(d: ReservationDetail): ReservationListItem {
  const g = d.guests?.find((x) => x.primaryGuest && !x.cancelled) ?? d.guests?.find((x) => !x.cancelled);
  return {
    id: d.id,
    reservationNumber: d.reservationNumber,
    guestFirstName: g?.firstName,
    guestLastName: g?.lastName,
    guestBirthYear: g?.birthDate ? Number(g.birthDate.slice(0, 4)) : undefined,
    arrivalDate: d.arrivalDate,
    departureDate: d.departureDate,
    status: d.status,
  };
}

/** Lokálne hľadanie v kartách z trezora: každé slovo dotazu sa musí nájsť v čísle karty alebo v mene hosťa. */
export async function searchLocal({ q, status, from, to, page }: SearchParams): Promise<SearchResult> {
  const terms = normalize(q).split(/\s+/).filter(Boolean);
  const hits: ReservationDetail[] = [];
  let keys: string[] = [];
  try {
    keys = await platform.vault.list(DS.reservation);
  } catch {
    // zamknutý trezor = žiadne karty
  }
  for (const key of keys) {
    const entry = await platform.vault.get<ReservationDetail>(DS.reservation, key).catch(() => null);
    const d = entry?.value;
    if (!d || (status && d.status !== status) || !overlaps(d.arrivalDate, d.departureDate, from, to)) continue;
    const hay = normalize([d.reservationNumber, ...(d.guests ?? []).flatMap((g) => [g.firstName, g.lastName])].join(' '));
    if (terms.every((t) => hay.includes(t))) hits.push(d);
  }
  const all = hits
    .map(toListItem)
    .sort((a, b) => (a.guestLastName ?? '').localeCompare(b.guestLastName ?? '', 'sk') || (a.id ?? 0) - (b.id ?? 0));
  return { mode: 'local', items: all.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE), page, totalElements: all.length };
}

/** Online `GET /api/reservations`; bez spojenia (alebo pri sieťovej chybe) lokálne v trezore. */
export async function searchReservations(params: SearchParams): Promise<SearchResult> {
  if (connection.getState() !== 'online') return searchLocal(params);
  try {
    const res = (
      await apiCall('get', '/api/reservations', {
        query: {
          q: params.q.trim() || undefined,
          status: params.status,
          from: params.from,
          // server berie `to` výlučne (príchod < to), v UI je „do“ vrátane
          to: params.to ? addDays(params.to, 1) : undefined,
          page: params.page,
          size: PAGE_SIZE,
        },
      })
    ).data;
    return { mode: 'online', items: res.content ?? [], page: res.page ?? params.page, totalElements: res.totalElements ?? 0 };
  } catch (e) {
    if (!isNetError(e)) throw e;
    connection.onNetworkError();
    return searchLocal(params);
  }
}

/** Detail karty: online zo servera (a uloží do trezora), offline z trezora (`reservation/<id>`). */
export const loadReservation = (id: number) =>
  offlineQuery<ReservationDetail>(DS.reservation, String(id), async () => ({
    status: 200,
    data: (await apiCall('get', '/api/reservations/{id}', { path: { id } })).data,
  }), undefined, reservationViewSpec);

/** Pobyty karty – len online (nie sú samostatnou sadou v trezore). */
export const loadStays = async (id: number): Promise<ReservationStay[]> =>
  (await apiCall('get', '/api/reservations/{id}/stays', { path: { id } })).data;

/** Úprava karty s optimistic locking (`version`); konflikt = `409`. */
export const updateReservation = async (id: number, body: ReservationUpdate): Promise<ReservationDetail> =>
  (await apiCall('patch', '/api/reservations/{id}', { path: { id }, body })).data;

/** Nový komentár karty (nedá sa upraviť, len skryť). */
export const addComment = async (id: number, text: string): Promise<ReservationComment> =>
  (await apiCall('post', '/api/reservations/{id}/comments', { path: { id }, body: { text } })).data;

/** Skrytie komentára (`RESERVATION_EDIT`). */
export const hideComment = async (id: number, commentId: number): Promise<void> => {
  await apiCall('post', '/api/reservations/{id}/comments/{commentId}/hide', { path: { id, commentId } });
};

/** Oprava údajov klienta (`RESERVATION_EDIT`); klient je zdieľaný medzi kartami, konflikt verzie = `409`. */
export const updateClient = async (id: number, body: ClientUpdate): Promise<ClientView> =>
  (await apiCall('put', '/api/clients/{id}', { path: { id }, body })).data;
