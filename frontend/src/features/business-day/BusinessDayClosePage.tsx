import { Alert, Button, Group, Loader, Modal, NativeSelect, Paper, Stack, Text, Title } from '@mantine/core';
import { originState } from '../../shared/navigation/origin';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { queryKeys } from '../../shared/api/queryKeys';
import { apiCall } from '../../shared/api/client';
import { ApiError } from '../../shared/api/errors';
import { errorText } from '../../shared/api/errorText';
import type { components } from '../../shared/api/schema';
import { syncEngine, useConnection, WriteAction } from '../../shared/offline';
import { receptionsInScope } from '../../shared/offline/scope';
import { closeBusinessDay, previewClose, type Finding } from './businessDayApi';
import { PrintableReport, usePrint } from './printReport';
import { ReportArchive } from './ReportArchive';
import { fmtDay as fmtDate } from './reportFormat';

type Me = components['schemas']['MeView'];



function FindingList({ kind, findings }: { kind: 'blocking' | 'info'; findings: Finding[] }) {
  const { t } = useTranslation();
  if (findings.length === 0) return null;
  const blocking = kind === 'blocking';
  return (
    <Paper withBorder radius="lg" bg="white" data-testid={`close-${kind}`} style={{ overflow: 'hidden' }}>
      <Text fw={600} p="sm" c={blocking ? 'orange.9' : 'brand.9'} bg={blocking ? 'orange.0' : 'brand.0'}>
        {t(`businessDay.${kind}Title`)}
      </Text>
      {findings.map((f, i) => (
        <Group key={`${f.ruleCode}-${f.reservationId ?? ''}-${i}`} p="sm" justify="space-between" data-testid={`close-${kind}-finding`} style={{ borderTop: '1px solid #EEF3F8' }}>
          <Text fw={500}>{f.message ?? f.ruleCode}</Text>
          <Group gap="sm">
            {f.reservationId !== undefined && (
              <Link to={`/reservations/${f.reservationId}`} state={originState(t('menu.businessDayClose'), '/business-day')}>{t('businessDay.openCard')}</Link>
            )}
            <Link to="/plan">{t('businessDay.openPlan')}</Link>
          </Group>
        </Group>
      ))}
    </Paper>
  );
}

/** Neprišlí: pri uzavretí dňa im server nastaví stav Neprišiel. */
function NoShowList({ findings }: { findings: Finding[] }) {
  const { t } = useTranslation();
  if (findings.length === 0) return null;
  return (
    <Paper withBorder radius="lg" bg="white" data-testid="close-noshow" style={{ overflow: 'hidden' }}>
      <Text fw={600} p="sm" c="orange.9" bg="orange.0">{t('businessDay.noShowTitle')}</Text>
      {findings.map((f, i) => (
        <Group key={`${f.reservationId ?? ''}-${i}`} p="sm" justify="space-between" data-testid="close-noshow-finding" style={{ borderTop: '1px solid #EEF3F8' }}>
          <Text fw={500}>{f.message ?? f.ruleCode}</Text>
          {f.reservationId !== undefined && (
            <Link to={`/reservations/${f.reservationId}`} state={originState(t('menu.businessDayClose'), '/business-day')}>{t('businessDay.openCard')}</Link>
          )}
        </Group>
      ))}
      <Text size="xs" c="dimmed" p="sm">{t('businessDay.noShowHint')}</Text>
    </Paper>
  );
}

/** Nočná uzávierka recepcie (HLD-ubytovanie kap. 5.8): kontrola → potvrdenie → nový obchodný deň. Len online. */
export function BusinessDayClosePage({ me }: { me: Me | null | undefined }) {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { canWrite } = useConnection();
  const [picked, setPicked] = useState<number | undefined>();
  const [confirming, setConfirming] = useState(false);
  const [closing, setClosing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const [view, setView] = useState<'close' | 'archive'>('close');
  const { printedAt, print } = usePrint();

  const receptions = useQuery({
    queryKey: queryKeys.receptions,
    queryFn: async () => (await apiCall('get', '/api/catalog/receptions')).data,
    enabled: canWrite,
  });
  const list = (receptions.data ?? []).filter((r) => r.id !== undefined && r.active !== false);
  const ids = receptionsInScope(me, 'BUSINESS_DAY_CLOSE', list.map((r) => r.id as number));
  const allowed = list.filter((r) => ids.includes(r.id as number));
  const receptionId =
    [picked, me?.defaultReceptionId].find((id) => id !== undefined && ids.includes(id)) ?? ids[0];

  const preview = useQuery({
    queryKey: ['business-day', 'close-preview', receptionId],
    queryFn: () => previewClose(receptionId as number),
    enabled: canWrite && receptionId !== undefined,
    gcTime: 0,
    staleTime: 0,
  });
  const p = preview.data;
  const blocking = p?.blocking ?? [];
  const noShows = (p?.info ?? []).filter((f) => f.ruleCode === 'NO_SHOW');
  const info = (p?.info ?? []).filter((f) => f.ruleCode !== 'NO_SHOW');
  const infoTotal = noShows.length + info.length;
  const canClose = p !== undefined && blocking.length === 0 && p.canClose !== false;

  const close = async () => {
    if (receptionId === undefined) return;
    setClosing(true);
    setError(null);
    try {
      const day = await closeBusinessDay(receptionId);
      setConfirming(false);
      setDone(day.businessDate ?? null);
      qc.setQueryData(queryKeys.businessDay(receptionId), day);
      void qc.invalidateQueries({ queryKey: queryKeys.businessDay(receptionId) });
      void qc.invalidateQueries({ queryKey: queryKeys.plan });
      void qc.invalidateQueries({ queryKey: ['reservations'] });
      void qc.invalidateQueries({ queryKey: ['movements'] });
      void qc.removeQueries({ queryKey: ['business-day', 'close-preview', receptionId] });
      void syncEngine.fullSync('business-day', { receptionId }).catch(() => {});
    } catch (e) {
      setConfirming(false);
      if (e instanceof ApiError && e.status === 409) {
        setError(t('businessDay.conflict'));
        await qc.invalidateQueries({ queryKey: queryKeys.businessDay(receptionId) });
        void preview.refetch();
      } else {
        setError(errorText(t, e));
      }
    } finally {
      setClosing(false);
    }
  };

  const noAccess = receptions.data !== undefined && allowed.length === 0;
  const receptionName = allowed.find((r) => r.id === receptionId)?.name ?? '';
  const dateText = p?.businessDate ? fmtDate(p.businessDate, i18n.language) : '';

  return (
    <Stack data-testid="business-day-page">
      <Group justify="space-between" className="report-noprint">
        <Title order={2}>{t('businessDay.title')}</Title>
        {view === 'close' && (
          <Group>
            <Button variant="default" disabled={!canWrite || !p?.report} onClick={print}>{t('businessDay.printReport')}</Button>
            <Button variant="default" disabled={!canWrite || receptionId === undefined} onClick={() => setView('archive')}>
              {t('businessDay.archive')}
            </Button>
          </Group>
        )}
      </Group>

      {!canWrite && <Alert color="orange" data-testid="close-offline">{t('businessDay.offline')}</Alert>}
      {noAccess && <Alert color="orange">{t('businessDay.noReception')}</Alert>}
      {error && <Alert color="red" data-testid="close-error">{error}</Alert>}
      {done && <Alert color="teal" data-testid="close-done">{t('businessDay.done', { date: fmtDate(done, i18n.language) })}</Alert>}

      {canWrite && allowed.length > 0 && (
        <NativeSelect
          className="report-noprint"
          label={t('shell.receptionLabel')}
          data={allowed.map((r) => ({ value: String(r.id), label: r.name ?? r.code ?? String(r.id) }))}
          value={receptionId === undefined ? '' : String(receptionId)}
          onChange={(e) => {
            setDone(null);
            setError(null);
            setPicked(Number(e.currentTarget.value));
          }}
          maw={320}
        />
      )}

      {canWrite && view === 'archive' && receptionId !== undefined && (
        <ReportArchive receptionId={receptionId} receptionName={receptionName} onBack={() => setView('close')} />
      )}

      {canWrite && view === 'close' && preview.isPending && receptionId !== undefined && <Loader size="sm" data-testid="close-loading" />}
      {canWrite && view === 'close' && preview.isError && <Alert color="red">{errorText(t, preview.error)}</Alert>}

      {canWrite && view === 'close' && p && (
        <>
          {p.report && <PrintableReport report={p.report} receptionName={receptionName} printedAt={printedAt} />}
          <Group grow align="stretch" className="report-noprint">
            <Paper withBorder p="md" radius="lg" bg="white">
              <Text size="xs" c="dimmed">{t('businessDay.closing')}</Text>
              <Text fw={700} size="xl" data-testid="close-date">{dateText}</Text>
            </Paper>
            <Paper withBorder p="md" radius="lg" bg="white">
              <Text size="xs" c="dimmed">{t('businessDay.blockingCount')}</Text>
              <Text fw={700} size="xl" c={blocking.length ? 'orange.9' : undefined} data-testid="close-blocking-count">{blocking.length}</Text>
            </Paper>
            <Paper withBorder p="md" radius="lg" bg="white">
              <Text size="xs" c="dimmed">{t('businessDay.infoCount')}</Text>
              <Text fw={700} size="xl" data-testid="close-info-count">{infoTotal}</Text>
            </Paper>
          </Group>
          <Stack className="report-noprint" data-testid="close-checks">
            <Title order={3}>{t('businessDay.checksTitle')}</Title>
            <FindingList kind="blocking" findings={blocking} />
            <NoShowList findings={noShows} />
            <FindingList kind="info" findings={info} />
            {blocking.length === 0 && infoTotal === 0 && <Text c="dimmed">{t('businessDay.noFindings')}</Text>}
          </Stack>
        </>
      )}

      {view === 'close' && <Paper withBorder p="md" radius="lg" bg="white" className="report-noprint">
        <Group justify="space-between">
          <Text maw={520}>{t('businessDay.irreversible')}</Text>
          <Group>
            <WriteAction>
              <Button variant="default" disabled={receptionId === undefined} loading={preview.isFetching} onClick={() => { setError(null); void preview.refetch(); }}>
                {t('businessDay.recheck')}
              </Button>
            </WriteAction>
            <WriteAction>
              <Button
                disabled={!canClose}
                title={!canClose && blocking.length > 0 ? t('businessDay.resolveFirst') : undefined}
                onClick={() => setConfirming(true)}
              >
                {t('businessDay.close')}
              </Button>
            </WriteAction>
          </Group>
        </Group>
      </Paper>}

      <Modal opened={confirming} onClose={() => setConfirming(false)} title={t('businessDay.confirmTitle')}>
        <Stack>
          <Text size="sm">{t('businessDay.confirmText', { date: dateText })}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirming(false)}>{t('shell.cancel')}</Button>
            <Button loading={closing} onClick={() => void close()}>{t('businessDay.confirmButton')}</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
