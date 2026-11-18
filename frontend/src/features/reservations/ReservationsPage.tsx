import { Alert, Badge, Button, Group, Loader, NativeSelect, Paper, Table, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useConnection } from '../../shared/offline';
import { PAGE_SIZE, searchReservations, type ReservationStatus } from './reservationsApi';

const STATUSES: ReservationStatus[] = ['RESERVED', 'CHECKED_IN', 'CHECKED_OUT', 'CANCELLED'];

/** Rezervačné karty: vyhľadávanie (online na serveri, offline v kartách z trezora) so stránkovaním. */
export function ReservationsPage() {
  const { t } = useTranslation();
  const { state } = useConnection();
  const [draft, setDraft] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<ReservationStatus | ''>('');
  const [page, setPage] = useState(0);
  const offline = state !== 'online';

  const result = useQuery({
    queryKey: ['reservations', 'search', offline, q, status, page],
    queryFn: () => searchReservations({ q, status: status || undefined, page }),
  });
  const data = result.data;
  const local = offline || data?.mode === 'local';
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / PAGE_SIZE)) : 1;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQ(draft);
  };

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="reservations-page">
      <Title order={2} mb="sm">{t('reservations.title')}</Title>
      <form onSubmit={submit} role="search">
        <Group align="flex-end" mb="md">
          <TextInput
            label={t('reservations.searchLabel')}
            placeholder={t('reservations.searchPlaceholder')}
            value={draft}
            onChange={(e) => setDraft(e.currentTarget.value)}
          />
          <NativeSelect
            label={t('reservations.statusLabel')}
            data={[{ value: '', label: t('reservations.statusAll') }, ...STATUSES.map((s) => ({ value: s, label: t(`plan.status.${s}`, s) }))]}
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.currentTarget.value as ReservationStatus | '');
            }}
          />
          <Button type="submit">{t('reservations.search')}</Button>
        </Group>
      </form>

      {local && (
        <Alert color="orange" mb="md" data-testid="reservations-local-warning">{t('reservations.localOnly')}</Alert>
      )}
      {result.isPending && <Loader size="sm" />}
      {result.isError && <Alert color="red">{t('errors.generic')}</Alert>}
      {data && data.items.length === 0 && (
        <Text c="dimmed" data-testid="reservations-empty">
          {data.mode === 'local' ? t('reservations.emptyLocal') : t('reservations.empty')}
        </Text>
      )}
      {data && data.items.length > 0 && (
        <Table data-testid="reservations-table">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('reservations.number')}</Table.Th>
              <Table.Th>{t('reservations.guest')}</Table.Th>
              <Table.Th>{t('reservations.dates')}</Table.Th>
              <Table.Th>{t('reservations.statusLabel')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {data.items.map((r) => (
              <Table.Tr key={r.id} data-testid="reservation-row">
                <Table.Td><Link to={`/reservations/${r.id}`}>{r.reservationNumber}</Link></Table.Td>
                <Table.Td>{[r.guestLastName, r.guestFirstName].filter(Boolean).join(' ')}</Table.Td>
                <Table.Td>{r.arrivalDate} – {r.departureDate}</Table.Td>
                <Table.Td>{r.status && <Badge variant="light">{t(`plan.status.${r.status}`, r.status)}</Badge>}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {data && data.totalElements > PAGE_SIZE && (
        <Group mt="md" gap="xs">
          <Button variant="default" size="compact-md" disabled={page === 0} onClick={() => setPage(page - 1)}>‹</Button>
          <Text size="sm" data-testid="reservations-page-info">{t('reservations.pageInfo', { page: page + 1, pages })}</Text>
          <Button variant="default" size="compact-md" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>›</Button>
        </Group>
      )}
    </Paper>
  );
}
