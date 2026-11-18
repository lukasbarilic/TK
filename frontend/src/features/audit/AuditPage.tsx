import { Alert, Button, Group, Loader, NativeSelect, Paper, SegmentedControl, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import { errorText } from '../../shared/api/errorText';
import { queryKeys } from '../../shared/api/queryKeys';
import { useConnection } from '../../shared/offline';
import { searchEvents, type AuditFilter } from './auditApi';
import { ClientAccess } from './ClientAccess';
import { EventDetailModal } from './EventDetailModal';
import { Pager } from './Pager';

const FILTER_KEYS = ['type', 'entityType', 'entityId', 'userId', 'from', 'to', 'operation'] as const;

/** Audit (HLD-ubytovanie kap. 7, F14): vyhľadávanie, detail zmeny a prístupy ku klientovi. Filtre sú v URL. Len online; prístup stráži `AUDIT_VIEW`. */
export function AuditPage() {
  const { t } = useTranslation();
  const { canWrite } = useConnection();
  const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'access' ? 'access' : 'events';
  const page = Math.max(0, Number(params.get('page')) || 0);
  const eventId = Number(params.get('event')) || null;
  const clientId = Number(params.get('clientId')) || null;
  const applied = Object.fromEntries(FILTER_KEYS.map((k) => [k, params.get(k) ?? ''])) as unknown as AuditFilter;
  const [draft, setDraft] = useState<AuditFilter>(applied);
  const [clientDraft, setClientDraft] = useState(clientId ? String(clientId) : '');

  const update = (changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(changes)) {
      if (v) next.set(k, v);
      else next.delete(k);
    }
    setParams(next);
  };
  const set = (k: keyof AuditFilter) => (v: string) => setDraft((d) => ({ ...d, [k]: v }));

  const events = useQuery({
    queryKey: queryKeys.auditEvents(applied, page),
    queryFn: () => searchEvents(applied, page),
    enabled: canWrite && view === 'events',
  });

  const apply = () => update({ ...draft, page: null });

  return (
    <Stack data-testid="audit-page-root">
      <Title order={2}>{t('audit.title')}</Title>
      {!canWrite && <Alert color="orange" data-testid="audit-offline">{t('audit.offline')}</Alert>}
      <SegmentedControl
        value={view}
        onChange={(v) => update({ view: v === 'access' ? 'access' : null, page: null })}
        data={[{ value: 'events', label: t('audit.tabEvents') }, { value: 'access', label: t('audit.tabAccess') }]}
      />

      {canWrite && view === 'events' && (
        <>
          <Group align="flex-end">
            <NativeSelect label={t('audit.type')} value={draft.type} onChange={(e) => set('type')(e.currentTarget.value)}
              data={[{ value: '', label: t('audit.all') }, 'WRITE', 'READ', 'LOGIN']} />
            <TextInput label={t('audit.entityType')} value={draft.entityType} onChange={(e) => set('entityType')(e.currentTarget.value)} />
            <TextInput label={t('audit.entityId')} value={draft.entityId} onChange={(e) => set('entityId')(e.currentTarget.value)} />
            <TextInput label={t('audit.userId')} value={draft.userId} onChange={(e) => set('userId')(e.currentTarget.value)} />
            <TextInput label={t('audit.operation')} value={draft.operation} onChange={(e) => set('operation')(e.currentTarget.value)} />
            <TextInput type="date" label={t('audit.from')} value={draft.from} onChange={(e) => set('from')(e.currentTarget.value)} />
            <TextInput type="date" label={t('audit.to')} value={draft.to} onChange={(e) => set('to')(e.currentTarget.value)} />
            <Button onClick={apply}>{t('audit.search')}</Button>
          </Group>

          {events.isPending && <Loader size="sm" />}
          {events.isError && <Alert color="red">{errorText(t, events.error)}</Alert>}
          {events.data && (
            <>
              <Paper withBorder radius="lg" bg="white" style={{ overflow: 'hidden' }}>
                <Table>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t('audit.colWhen')}</Table.Th>
                      <Table.Th>{t('audit.colUser')}</Table.Th>
                      <Table.Th>{t('audit.colType')}</Table.Th>
                      <Table.Th>{t('audit.colOperation')}</Table.Th>
                      <Table.Th>{t('audit.colTarget')}</Table.Th>
                      <Table.Th>{t('audit.colOutcome')}</Table.Th>
                      <Table.Th />
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {(events.data.content ?? []).map((e) => (
                      <Table.Tr key={e.id} data-testid="audit-row">
                        <Table.Td>{e.occurredAt ? new Date(e.occurredAt).toLocaleString() : ''}</Table.Td>
                        <Table.Td>{e.username}</Table.Td>
                        <Table.Td>{e.eventType}</Table.Td>
                        <Table.Td>{e.operation}</Table.Td>
                        <Table.Td>{e.targetType ? `${e.targetType} #${e.targetId ?? ''}` : ''}</Table.Td>
                        <Table.Td>{e.outcome}</Table.Td>
                        <Table.Td>
                          <Button size="xs" variant="default" aria-label={`${t('audit.detail')} ${e.id}`} onClick={() => update({ event: String(e.id) })}>{t('audit.detail')}</Button>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                    {(events.data.content ?? []).length === 0 && <Table.Tr><Table.Td colSpan={7}><Text c="dimmed">{t('audit.empty')}</Text></Table.Td></Table.Tr>}
                  </Table.Tbody>
                </Table>
              </Paper>
              <Pager page={page} total={events.data.totalElements ?? 0} onPage={(p) => update({ page: p ? String(p) : null })} />
            </>
          )}
        </>
      )}

      {canWrite && view === 'access' && (
        <>
          <Group align="flex-end">
            <TextInput label={t('audit.clientId')} value={clientDraft} onChange={(e) => setClientDraft(e.currentTarget.value)} />
            <Button onClick={() => update({ clientId: Number(clientDraft) > 0 ? String(Number(clientDraft)) : null, page: null })}>{t('audit.search')}</Button>
          </Group>
          {clientId && <ClientAccess clientId={clientId} page={page} onPage={(p) => update({ page: p ? String(p) : null })} />}
        </>
      )}

      {canWrite && eventId && <EventDetailModal id={eventId} onClose={() => update({ event: null })} />}
    </Stack>
  );
}
