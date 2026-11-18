import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../shared/api/errors';
import { errorText } from '../../shared/api/errorText';
import type { ChangeResult } from './movesApi';
import { handlePlanEvent } from './usePlanLive';

/** Jedna zmena ubytovania: dostane potvrdené upozornenia a vráti dotknuté izby. */
export interface Change {
  /** Izby, ktoré zmena zjavne zasiahne (obnovia sa aj pri konflikte). */
  roomIds: number[];
  run: (confirm: string[]) => Promise<ChangeResult | { auditEventId?: number }>;
  /** Po úspechu (napr. vyprázdnenie odkladiska). */
  onSuccess?: () => void;
  /** Pri chybe (napr. označenie položky dávky, ktorá zlyhala). */
  onError?: (e: unknown) => void;
}

const roomsOf = (r: ChangeResult | { auditEventId?: number }): number[] =>
  'plan' in r ? (r.plan.rooms ?? []).flatMap((x) => (x.roomId === undefined ? [] : [x.roomId])) : [];

/**
 * Vykonanie zmeny ubytovania podľa DLD kap. 8: potvrditeľné 409 → dialóg a opakovanie s `confirm`; iná chyba
 * (konflikt verzie, obsadené lôžko, …) → upozornenie a obnovenie dotknutých izieb, bez opakovania.
 */
export function useMoveActions(receptionId: number | undefined) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirming, setConfirming] = useState<{ warnings: string[] } | null>(null);
  const pending = useRef<Change | null>(null);

  const refresh = useCallback(
    async (roomIds: number[], auditEventId?: number) => {
      void qc.invalidateQueries({ queryKey: ['reservations'] });
      if (receptionId === undefined) return;
      await handlePlanEvent(qc, receptionId, {
        type: 'plan-changed',
        data: { receptionId, roomIds: [...new Set(roomIds)], auditEventId },
      });
    },
    [qc, receptionId],
  );

  const attempt = useCallback(
    async (change: Change, confirm: string[]) => {
      setBusy(true);
      try {
        const res = await change.run(confirm);
        pending.current = null;
        setConfirming(null);
        setNotice(null);
        change.onSuccess?.();
        await refresh([...change.roomIds, ...roomsOf(res)], res.auditEventId);
      } catch (e) {
        change.onError?.(e);
        if (e instanceof ApiError && e.status === 409 && e.confirmable && e.warnings.length > 0) {
          pending.current = change;
          setConfirming({ warnings: e.warnings });
          return;
        }
        pending.current = null;
        setConfirming(null);
        if (e instanceof ApiError && e.status === 409) {
          setNotice(`${errorText(t, e)} ${t('plan.moves.refreshed')}`);
          await refresh(change.roomIds);
        } else {
          setNotice(errorText(t, e));
        }
      } finally {
        setBusy(false);
      }
    },
    [refresh, t],
  );

  return {
    busy,
    notice,
    dismissNotice: useCallback(() => setNotice(null), []),
    warnings: confirming?.warnings ?? [],
    run: useCallback((change: Change) => attempt(change, []), [attempt]),
    confirm: useCallback(() => {
      const change = pending.current;
      const warnings = confirming?.warnings ?? [];
      if (change) void attempt(change, warnings);
    }, [attempt, confirming]),
    cancelConfirm: useCallback(() => {
      pending.current = null;
      setConfirming(null);
    }, []),
  };
}
