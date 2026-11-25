import { Alert, Badge, Button, Group, Loader, NativeSelect, Paper, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { originState } from '../../shared/navigation/origin';
import { useQuery } from '@tanstack/react-query';
import { useState, type CSSProperties, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { useConnection } from '../../shared/offline';
import { diffDays } from '../../shared/time';
import { PAGE_SIZE, searchReservations, type ReservationStatus } from './reservationsApi';

const STATUSES: ReservationStatus[] = ['RESERVED', 'CHECKED_IN', 'CHECKED_OUT', 'NO_SHOW', 'CANCELLED'];

// Sekundárny text s kontrastom ≥ 4,5:1 na bielom pozadí (Mantine „dimmed“ je svetlejší).
const MUTED = '#4A5D75';

/** Farby stavov podľa docs/frontend/ui/README.md (v2), rovnaké ako v pláne. */
const STATUS_STYLE: Record<ReservationStatus, CSSProperties> = {
  RESERVED: { background: '#CFE3F7', color: '#154A85', border: '1px dashed #3F79B8' },
  CHECKED_IN: { background: '#1E5FA8', color: '#FFFFFF', border: '1px solid #154A85' },
  CHECKED_OUT: { background: '#D5DCE4', color: '#13243A', border: '1px solid #B5C0CC' },
  NO_SHOW: { background: '#FFF1E0', color: '#7A3705', border: '1px solid #D2691E' },
  CANCELLED: { background: '#F7E7DE', color: '#7A3705', border: '1px solid #E2C4B3' },
};

/** '2026-11-19' → '19. 11. 2026'; bez lokálneho `Date`, aby sa deň neposunul. */
const fmtDay = (day?: string) => {
  const m = day && /^(\d{4})-(\d{2})-(\d{2})$/.exec(day);
  return m ? `${Number(m[3])}. ${Number(m[2])}. ${m[1]}` : (day ?? '');
};

const nightsOf = (from?: string, to?: string): number | undefined => {
  if (!from || !to) return undefined;
  try {
    return diffDays(from, to);
  } catch {
    return undefined;
  }
};

/**
 * Rezervačné karty: vyhľadávanie podľa mena/čísla, stavu a obdobia pobytu (online na serveri,
 * offline v kartách z trezora) so stránkovaním. Riadok vedie na detail karty.
 */
export function ReservationsPage({ onNew }: { onNew?: () => void } = {}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { state, canWrite } = useConnection();
  const [draft, setDraft] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<ReservationStatus | ''>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const offline = state !== 'online';
  const rangeInvalid = from !== '' && to !== '' && to < from;
  const filtered = q !== '' || draft !== '' || status !== '' || from !== '' || to !== '';

  const result = useQuery({
    queryKey: ['reservations', 'search', offline, q, status, from, to, page],
    queryFn: () => searchReservations({ q, status: status || undefined, from: from || undefined, to: to || undefined, page }),
    enabled: !rangeInvalid,
  });
  const data = result.data;
  const local = offline || data?.mode === 'local';
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / PAGE_SIZE)) : 1;
  const detailState = originState(t('menu.reservations'), '/reservations');

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQ(draft);
  };
  const clear = () => {
    setDraft('');
    setQ('');
    setStatus('');
    setFrom('');
    setTo('');
    setPage(0);
  };

  return (
    <Paper withBorder p="lg" radius="lg" bg="white" data-testid="reservations-page">
      <Group justify="space-between" align="flex-start" mb="md">
        <Stack gap={2}>
          <Title order={2}>{t('reservations.title')}</Title>
          {data && (
            <Text size="sm" c={MUTED} data-testid="reservations-count">
              {t('reservations.count', { count: data.totalElements })}
            </Text>
          )}
        </Stack>
        {onNew && canWrite && <Button onClick={onNew} data-testid="reservations-new">{t('reservations.new')}</Button>}
      </Group>

      <Paper component="form" onSubmit={submit} role="search" p="sm" radius="md" bg="#EEF5FC" withBorder mb="md" style={{ borderColor: '#C9DCEF' }}>
        <Group align="flex-start" gap="sm" wrap="wrap">
          <TextInput
            label={t('reservations.searchLabel')}
            placeholder={t('reservations.searchPlaceholder')}
            value={draft}
            onChange={(e) => setDraft(e.currentTarget.value)}
            style={{ flex: '1 1 260px', minWidth: 220 }}
          />
          <NativeSelect
            label={t('reservations.statusLabel')}
            data={[{ value: '', label: t('reservations.statusAll') }, ...STATUSES.map((s) => ({ value: s, label: t(`plan.status.${s}`, s) }))]}
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.currentTarget.value as ReservationStatus | '');
            }}
            w={170}
          />
          <TextInput
            type="date"
            label={t('reservations.from')}
            value={from}
            onChange={(e) => {
              setPage(0);
              setFrom(e.currentTarget.value);
            }}
            w={170}
          />
          <TextInput
            type="date"
            label={t('reservations.to')}
            value={to}
            min={from || undefined}
            error={rangeInvalid ? t('reservations.rangeInvalid') : undefined}
            onChange={(e) => {
              setPage(0);
              setTo(e.currentTarget.value);
            }}
            w={170}
          />
          <Group gap="xs" mt={25}>
            <Button type="submit">{t('reservations.search')}</Button>
            {filtered && (
              <Button variant="subtle" onClick={clear} data-testid="reservations-clear">{t('reservations.clearFilters')}</Button>
            )}
          </Group>
        </Group>
      </Paper>

      {local && (
        <Alert color="orange" mb="md" data-testid="reservations-local-warning">{t('reservations.localOnly')}</Alert>
      )}
      {result.isLoading && <Loader size="sm" />}
      {result.isError && <Alert color="red">{t('errors.generic')}</Alert>}
      {data && data.items.length === 0 && (
        <Paper p="xl" radius="md" withBorder style={{ borderStyle: 'dashed', borderColor: '#C9DCEF' }}>
          <Text ta="center" c={MUTED} data-testid="reservations-empty">
            {data.mode === 'local' ? t('reservations.emptyLocal') : t('reservations.empty')}
          </Text>
        </Paper>
      )}
      {data && data.items.length > 0 && (
        <Table.ScrollContainer minWidth={720}>
          <Table data-testid="reservations-table" highlightOnHover verticalSpacing="sm" horizontalSpacing="md" borderColor="#E3ECF6">
            <Table.Thead bg="#F6FAFE">
              <Table.Tr>
                <Table.Th>{t('reservations.guest')}</Table.Th>
                <Table.Th>{t('reservations.dates')}</Table.Th>
                <Table.Th>{t('reservations.category')}</Table.Th>
                <Table.Th>{t('reservations.number')}</Table.Th>
                <Table.Th>{t('reservations.statusLabel')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {data.items.map((r) => {
                const name = [r.guestLastName, r.guestFirstName].filter(Boolean).join(' ') || r.reservationNumber;
                const nights = nightsOf(r.arrivalDate, r.departureDate);
                const url = `/reservations/${r.id}`;
                return (
                  <Table.Tr key={r.id} data-testid="reservation-row" style={{ cursor: 'pointer' }} onClick={() => navigate(url, { state: detailState })}>
                    <Table.Td>
                      <Link to={url} state={detailState} onClick={(e) => e.stopPropagation()} style={{ color: '#13243A', fontWeight: 600, textDecoration: 'none' }}>
                        {name}
                      </Link>
                      {r.guestBirthYear !== undefined && (
                        <Text size="xs" c={MUTED}>{t('reservations.birthYear', { year: r.guestBirthYear })}</Text>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" style={{ whiteSpace: 'nowrap' }}>{fmtDay(r.arrivalDate)} – {fmtDay(r.departureDate)}</Text>
                      {nights !== undefined && <Text size="xs" c={MUTED}>{t('stays.nights', { count: nights })}</Text>}
                    </Table.Td>
                    <Table.Td><Text size="sm">{r.bookedCategoryName ?? '–'}</Text></Table.Td>
                    <Table.Td><Text size="sm" c={MUTED} ff="monospace">{r.reservationNumber}</Text></Table.Td>
                    <Table.Td>
                      {r.status && (
                        <Badge radius="sm" style={STATUS_STYLE[r.status]}>{t(`plan.status.${r.status}`, r.status)}</Badge>
                      )}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {data && data.totalElements > 0 && (
        <Group mt="md" justify="space-between">
          <Text size="sm" c={MUTED} data-testid="reservations-showing">
            {t('reservations.showing', {
              from: page * PAGE_SIZE + 1,
              to: Math.min((page + 1) * PAGE_SIZE, data.totalElements),
              total: data.totalElements,
            })}
          </Text>
          {data.totalElements > PAGE_SIZE && (
            <Group gap="xs">
              <Button variant="default" size="compact-md" aria-label={t('reservations.prevPage')} disabled={page === 0} onClick={() => setPage(page - 1)}>‹</Button>
              <Text size="sm" data-testid="reservations-page-info">{t('reservations.pageInfo', { page: page + 1, pages })}</Text>
              <Button variant="default" size="compact-md" aria-label={t('reservations.nextPage')} disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>›</Button>
            </Group>
          )}
        </Group>
      )}
    </Paper>
  );
}
