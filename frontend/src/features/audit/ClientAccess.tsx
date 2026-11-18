import { Alert, Badge, Loader, Paper, Table, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { errorText } from '../../shared/api/errorText';
import { queryKeys } from '../../shared/api/queryKeys';
import { clientAccess } from './auditApi';
import { Pager } from './Pager';

/** Kto a kedy čítal údaje klienta; záznamy z offline kópie sú označené OFFLINE / CACHED (B04). */
export function ClientAccess({ clientId, page, onPage }: { clientId: number; page: number; onPage: (p: number) => void }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: queryKeys.auditAccess(clientId, page), queryFn: () => clientAccess(clientId, page) });
  if (q.isPending) return <Loader size="sm" />;
  if (q.isError) return <Alert color="red">{errorText(t, q.error)}</Alert>;
  const rows = q.data.content ?? [];
  return (
    <>
      <Paper withBorder radius="lg" bg="white" style={{ overflow: 'hidden' }}>
        <Table>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('audit.colWhen')}</Table.Th>
              <Table.Th>{t('audit.colUser')}</Table.Th>
              <Table.Th>{t('audit.colOperation')}</Table.Th>
              <Table.Th>{t('audit.colScope')}</Table.Th>
              <Table.Th>{t('audit.colSource')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r, i) => (
              <Table.Tr key={`${r.eventId}-${i}`} data-testid="access-row">
                <Table.Td>{r.occurredAt ? new Date(r.occurredAt).toLocaleString() : ''}</Table.Td>
                <Table.Td>{r.username}</Table.Td>
                <Table.Td>{r.operation}</Table.Td>
                <Table.Td>{r.dataScope}</Table.Td>
                <Table.Td>
                  {r.source === 'OFFLINE' || r.source === 'CACHED' ? <Badge color="orange" data-testid="access-source">{r.source}</Badge> : r.source}
                </Table.Td>
              </Table.Tr>
            ))}
            {rows.length === 0 && <Table.Tr><Table.Td colSpan={5}><Text c="dimmed">{t('audit.empty')}</Text></Table.Td></Table.Tr>}
          </Table.Tbody>
        </Table>
      </Paper>
      <Pager page={page} total={q.data.totalElements ?? 0} onPage={onPage} />
    </>
  );
}
