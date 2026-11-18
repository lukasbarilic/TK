import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';

type S = components['schemas'];
export type AuditEvent = S['EventSummary'];
export type AuditEventDetail = S['EventDetail'];
export type AuditChange = S['ChangeView'];
export type AuditAccess = S['AccessView'];
export type AuditPage<T> = { content?: T[]; page?: number; size?: number; totalElements?: number };

export interface AuditFilter {
  type: string;
  entityType: string;
  entityId: string;
  userId: string;
  from: string;
  to: string;
  operation: string;
}

export const PAGE_SIZE = 20;

const num = (v: string) => (v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : undefined);
const text = (v: string) => (v.trim() !== '' ? v.trim() : undefined);
// Dátum z poľa `date` (YYYY-MM-DD) → okamih; `to` je vrátane celého dňa.
const from = (v: string) => (v ? `${v}T00:00:00Z` : undefined);
const to = (v: string) => (v ? `${v}T23:59:59Z` : undefined);

export const searchEvents = async (f: AuditFilter, page: number): Promise<AuditPage<AuditEvent>> =>
  (
    await apiCall('get', '/api/audit/events', {
      query: {
        type: text(f.type),
        entityType: text(f.entityType),
        entityId: num(f.entityId),
        userId: num(f.userId),
        from: from(f.from),
        to: to(f.to),
        operation: text(f.operation),
        page,
        size: PAGE_SIZE,
      },
    })
  ).data;

export const getEvent = async (id: number): Promise<AuditEventDetail> =>
  (await apiCall('get', '/api/audit/events/{id}', { path: { id } })).data;

export const clientAccess = async (clientId: number, page: number): Promise<AuditPage<AuditAccess>> =>
  (await apiCall('get', '/api/audit/clients/{clientId}/access', { path: { clientId }, query: { page, size: PAGE_SIZE } })).data;
