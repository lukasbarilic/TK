import { Alert, Button, Group, Modal, Textarea } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { errorText } from '../api/errorText';
import { ApiError } from '../api/errors';
import { putLocal } from '../offline';
import { DS } from '../offline/datasets';
import { ConfirmWarningsDialog } from '../ui/ConfirmWarningsDialog';
import { runStatusAction, type StatusAction, type StatusDetail } from './statusApi';

interface Req {
  action: StatusAction;
  id: number;
  confirm?: string[];
  reason?: string;
}

const MAX_REASON = 500;

/**
 * Zmena stavu karty z ktoréhokoľvek miesta (karta, plán, príchody): storno cez dialóg s dôvodom, upozornenia cez
 * `<ConfirmWarningsDialog>`, chyby so slovenským textom. Po úspechu sa obnoví karta, plán aj príchody/odchody.
 */
export function useStatusActions(onDone?: (d: StatusDetail) => void): { request: (a: StatusAction, id: number) => void; busy: boolean; node: ReactNode } {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [cancelFor, setCancelFor] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string>();
  const last = useRef<Req | null>(null);

  const refresh = async () => {
    await Promise.all([
      qc.invalidateQueries({ queryKey: ['reservations'] }),
      qc.invalidateQueries({ queryKey: ['plan'] }),
      qc.invalidateQueries({ queryKey: ['movements'] }),
    ]);
  };

  const run = useMutation({
    mutationFn: (r: Req) => runStatusAction(r.action, r.id, { confirm: r.confirm, reason: r.reason }),
    onSuccess: async (d, r) => {
      setWarnings([]);
      await putLocal(DS.reservation, String(r.id), d).catch(() => undefined);
      await refresh();
      onDone?.(d);
    },
    onError: (e, r) => {
      if (e instanceof ApiError && e.status === 409 && e.confirmable) {
        last.current = r;
        setWarnings(e.warnings);
        return;
      }
      setWarnings([]);
      setError(errorText(t, e));
      void refresh();
    },
  });

  const request = (action: StatusAction, id: number) => {
    setError(undefined);
    if (action === 'cancel') {
      setReason('');
      setCancelFor(id);
      return;
    }
    run.mutate({ action, id });
  };

  const node = (
    <>
      {error && (
        <Alert color="red" mb="sm" withCloseButton onClose={() => setError(undefined)} data-testid="status-error">
          {error}
        </Alert>
      )}
      <Modal opened={cancelFor !== null} onClose={() => setCancelFor(null)} title={t('status.cancelTitle')} centered>
        <Textarea
          label={t('status.reasonLabel')}
          value={reason}
          maxLength={MAX_REASON}
          rows={3}
          onChange={(e) => setReason(e.currentTarget.value)}
          data-autofocus
        />
        <Group justify="flex-end" mt="md">
          <Button variant="default" onClick={() => setCancelFor(null)}>{t('status.cancelBack')}</Button>
          <Button
            color="red"
            disabled={reason.trim() === ''}
            onClick={() => {
              const id = cancelFor;
              setCancelFor(null);
              if (id !== null) run.mutate({ action: 'cancel', id, reason: reason.trim() });
            }}
          >
            {t('status.cancelSubmit')}
          </Button>
        </Group>
      </Modal>
      <ConfirmWarningsDialog
        warnings={warnings}
        loading={run.isPending}
        onConfirm={() => last.current && run.mutate({ ...last.current, confirm: warnings })}
        onCancel={() => setWarnings([])}
      />
    </>
  );

  return { request, busy: run.isPending, node };
}
