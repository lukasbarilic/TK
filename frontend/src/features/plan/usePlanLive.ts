import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { queryKeys } from '../../shared/api/queryKeys';
import { connection, markEventHandled, wasEventHandled, type OfflineResult } from '../../shared/offline';
import type { LiveEvent } from '../../shared/platform/types';
import { loadRooms } from './planApi';
import type { StoredPlan } from './types';

export const planDataKey = (receptionId: number) => [...queryKeys.plan, 'data', receptionId] as const;

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;

/** Nahradí v uloženom pláne dané izby čerstvými (ostatné ostanú nedotknuté). */
export function replaceRooms(stored: StoredPlan, roomIds: number[], fresh: StoredPlan['plan']): StoredPlan {
  const touched = new Set(roomIds);
  const rooms = [
    ...(stored.plan.rooms ?? []).filter((r) => r.roomId === undefined || !touched.has(r.roomId)),
    ...(fresh.rooms ?? []),
  ];
  return { ...stored, plan: { ...stored.plan, rooms } };
}

/**
 * Spracuje živú udalosť pre plán (HLD kap. 6.4):
 * - `plan-changed`: dotiahne len dotknuté izby a vymení ich v načítanom pláne; udalosť s už spracovaným
 *   `auditEventId` (vlastná zmena alebo duplicita) sa preskočí;
 * - `business-day-changed`: obnoví celý plán tejto recepcie.
 */
export async function handlePlanEvent(qc: QueryClient, receptionId: number, e: LiveEvent): Promise<void> {
  if (!isRecord(e.data) || e.data.receptionId !== receptionId) return;
  if (e.type === 'business-day-changed') {
    await qc.invalidateQueries({ queryKey: queryKeys.plan });
    await qc.invalidateQueries({ queryKey: queryKeys.businessDay(receptionId) });
    return;
  }
  if (e.type !== 'plan-changed') return;

  const { auditEventId, from, to } = e.data;
  const roomIds = Array.isArray(e.data.roomIds) ? e.data.roomIds.filter((x): x is number => typeof x === 'number') : [];
  if (typeof auditEventId === 'number') {
    if (wasEventHandled(auditEventId)) return;
    markEventHandled(auditEventId);
  }
  const key = planDataKey(receptionId);
  if (roomIds.length === 0) {
    await qc.invalidateQueries({ queryKey: key });
    return;
  }
  for (const [queryKey, data] of qc.getQueriesData<OfflineResult<StoredPlan>>({ queryKey: key })) {
    if (!data || data.empty) continue;
    const w = data.data;
    if (typeof from === 'string' && typeof to === 'string' && (from > w.to || to < w.from)) continue;
    try {
      const fresh = await loadRooms(receptionId, roomIds, w.from, w.to);
      qc.setQueryData<OfflineResult<StoredPlan>>(queryKey, (prev) =>
        prev && !prev.empty
          ? { ...prev, data: replaceRooms(prev.data, roomIds, fresh), source: 'server', syncedAt: new Date().toISOString() }
          : prev,
      );
    } catch {
      await qc.invalidateQueries({ queryKey }); // nepodarilo sa → radšej celý plán
    }
  }
}

export function usePlanLive(receptionId: number | undefined): void {
  const qc = useQueryClient();
  useEffect(() => {
    if (receptionId === undefined) return;
    return connection.onLiveEvent((e) => void handlePlanEvent(qc, receptionId, e));
  }, [qc, receptionId]);
}
