import type { QueryClient } from '@tanstack/react-query';
import { apiCall } from '../../shared/api/client';
import type { components } from '../../shared/api/schema';
import { queryKeys } from '../../shared/api/queryKeys';
import { markEventHandled, putLocal, type OfflineResult } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { platform } from '../../shared/platform';

type Plan = components['schemas']['Plan'];
/** Plán s oknom, ako ho drží plán v cache a trezore (`features/plan/types.ts`). */
interface StoredPlan {
  from: string;
  to: string;
  plan: Plan;
}

const fetchRooms = async (receptionId: number, roomIds: number[], from: string, to: string): Promise<Plan> =>
  (await apiCall('get', '/api/accommodation-plan', { query: { receptionId, roomIds, from, to } })).data;

const replaceRooms = (stored: StoredPlan, roomIds: number[], fresh: Plan): StoredPlan => ({
  ...stored,
  plan: {
    ...stored.plan,
    rooms: [...(stored.plan.rooms ?? []).filter((r) => r.roomId === undefined || !roomIds.includes(r.roomId)), ...(fresh.rooms ?? [])],
  },
});

/**
 * Po úspešnom ubytovaní obnoví dotknuté izby v načítanom pláne (cache) aj v trezore a zaznačí vlastnú
 * udalosť ako spracovanú (DLD kap. 9.3). Ak sa izby nepodarí dotiahnuť, plán sa označí za neaktuálny.
 */
export async function refreshAfterStay(
  qc: QueryClient,
  o: { receptionId: number | undefined; roomId: number | undefined; from: string; to: string; auditEventId?: number },
): Promise<void> {
  if (o.auditEventId !== undefined) markEventHandled(o.auditEventId);
  void qc.invalidateQueries({ queryKey: ['reservations'] });
  if (o.receptionId === undefined || o.roomId === undefined) {
    await qc.invalidateQueries({ queryKey: queryKeys.plan });
    return;
  }
  const { receptionId, roomId } = o;
  const touches = (w: { from: string; to: string }) => !(o.from > w.to || o.to < w.from);
  try {
    for (const [key, data] of qc.getQueriesData<OfflineResult<StoredPlan>>({ queryKey: [...queryKeys.plan, 'data', receptionId] })) {
      if (!data || data.empty || !touches(data.data)) continue;
      const fresh = await fetchRooms(receptionId, [roomId], data.data.from, data.data.to);
      qc.setQueryData<OfflineResult<StoredPlan>>(key, (prev) =>
        prev && !prev.empty ? { ...prev, data: replaceRooms(prev.data, [roomId], fresh), source: 'server', syncedAt: new Date().toISOString() } : prev,
      );
    }
    const entry = await platform.vault.get<StoredPlan>(DS.plan, String(receptionId)).catch(() => null);
    if (entry && touches(entry.value)) {
      const fresh = await fetchRooms(receptionId, [roomId], entry.value.from, entry.value.to);
      await putLocal(DS.plan, String(receptionId), replaceRooms(entry.value, [roomId], fresh));
    }
  } catch {
    await qc.invalidateQueries({ queryKey: queryKeys.plan });
  }
}
