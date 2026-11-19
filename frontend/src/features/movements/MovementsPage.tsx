import { Alert, Badge, Button, Group, Loader, NativeSelect, Paper, Table, Text, Title } from '@mantine/core';
import { originState } from '../../shared/navigation/origin';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import type { components } from '../../shared/api/schema';
import { DataAge, useConnection, WriteAction } from '../../shared/offline';
import { statusActions, useStatusActions, type StatusAction } from '../../shared/status';
import { receptionsInScope } from '../../shared/offline/scope';
import { addDays } from '../../shared/time';
import { loadBusinessDay, loadMovements, loadReceptions, sortMovements, type Movement } from './movementsApi';

type Me = components['schemas']['MeView'];

const PRINT_CSS = `@media print {
  body * { visibility: hidden; }
  [data-testid="movements-page"], [data-testid="movements-page"] * { visibility: visible; }
  [data-testid="movements-page"] { position: absolute; left: 0; top: 0; width: 100%; border: 0; }
  .movements-noprint { display: none !important; }
}`;

const fmtDay = (day: string, lang: string) =>
  new Date(`${day}T00:00:00Z`).toLocaleDateString(lang, { weekday: 'short', day: 'numeric', month: 'numeric', timeZone: 'UTC' });

/** Karta bez lôžka, ktorej treba pridať lôžko (dialóg dodáva `app/`). */
export interface AssignCard {
  id: number;
  reservationNumber?: string;
}
export type RenderAssignBed = (card: AssignCard, onClose: () => void) => ReactNode;

interface ListProps {
  kind: 'arrivals' | 'departures';
  rows: Movement[];
  me: Me | null | undefined;
  /** Akcie sa ponúkajú len pre dnešný deň (príchod v budúcnosti server odmietne). */
  actionable: boolean;
  onAction: (a: StatusAction, id: number) => void;
  onAssign?: (m: Movement) => void;
}

function MovementList({ kind, rows, me, actionable, onAction, onAssign }: ListProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const done = rows.filter((m) => (kind === 'arrivals' ? m.status !== 'RESERVED' : m.status === 'CHECKED_OUT')).length;
  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid={`movements-${kind}`}>
      <Group gap="xs" mb="xs">
        <Title order={3}>{t(`movements.${kind}`)}</Title>
        <Badge variant="light" data-testid={`movements-${kind}-count`}>{rows.length}</Badge>
        <Text size="sm" c="dimmed" data-testid={`movements-${kind}-progress`}>
          {t(kind === 'arrivals' ? 'movements.arrivedProgress' : 'movements.departedProgress', { done, total: rows.length })}
        </Text>
      </Group>
      {rows.length === 0 ? (
        <Text c="dimmed" data-testid={`movements-${kind}-empty`}>{t('movements.empty')}</Text>
      ) : (
        <Table highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('movements.room')}</Table.Th>
              <Table.Th>{t('movements.guest')}</Table.Th>
              <Table.Th>{t('movements.card')}</Table.Th>
              <Table.Th>{t('movements.persons')}</Table.Th>
              <Table.Th>{t('movements.stay')}</Table.Th>
              <Table.Th>{t('movements.payer')}</Table.Th>
              <Table.Th>{t('movements.status')}</Table.Th>
              {actionable && <Table.Th>{t('movements.actions')}</Table.Th>}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((m, i) => (
              <Table.Tr
                key={`${m.reservationId}-${m.roomNumber ?? ''}-${m.bedNumber ?? ''}-${i}`}
                data-testid="movement-row"
                tabIndex={0}
                role="link"
                style={{ cursor: 'pointer' }}
                onClick={() => m.reservationId !== undefined && navigate(`/reservations/${m.reservationId}`, { state: originState(t('menu.movements'), '/movements') })}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && m.reservationId !== undefined) navigate(`/reservations/${m.reservationId}`, { state: originState(t('menu.movements'), '/movements') });
                }}
              >
                <Table.Td>{m.roomNumber ? `${m.roomNumber}${m.bedNumber ? `/${m.bedNumber}` : ''}` : t('movements.noRoom')}</Table.Td>
                <Table.Td>{m.guestName}{m.birthYear ? ` (${m.birthYear})` : ''}</Table.Td>
                <Table.Td>{m.reservationNumber}</Table.Td>
                <Table.Td>{m.guestCount}</Table.Td>
                <Table.Td>{m.arrivalDate} – {m.departureDate}</Table.Td>
                <Table.Td>{m.payer?.name}</Table.Td>
                <Table.Td>{m.status && <Badge variant="light">{t(`plan.status.${m.status}`, m.status)}</Badge>}</Table.Td>
                {actionable && (
                  <Table.Td onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
                    <RowAction kind={kind} m={m} me={me} onAction={onAction} onAssign={onAssign} />
                  </Table.Td>
                )}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Paper>
  );
}

function RowAction({ kind, m, me, onAction, onAssign }: { kind: 'arrivals' | 'departures'; m: Movement; me: Me | null | undefined; onAction: ListProps['onAction']; onAssign?: ListProps['onAssign'] }) {
  const { t } = useTranslation();
  if (m.reservationId === undefined) return null;
  const id = m.reservationId;
  const allowed = statusActions(m.status, me);
  if (kind === 'arrivals' && !m.roomNumber && m.status !== 'CHECKED_OUT') {
    return onAssign ? (
      <WriteAction>
        <Button size="compact-sm" variant="light" onClick={() => onAssign(m)}>{t('movements.assignBed')}</Button>
      </WriteAction>
    ) : null;
  }
  if (kind === 'arrivals' && allowed.includes('checkIn')) {
    return (
      <WriteAction>
        <Button size="compact-sm" onClick={() => onAction('checkIn', id)}>{t('movements.checkIn')}</Button>
      </WriteAction>
    );
  }
  if (kind === 'departures' && allowed.includes('checkOut')) {
    return (
      <WriteAction>
        <Button size="compact-sm" onClick={() => onAction('checkOut', id)}>{t('movements.checkOut')}</Button>
      </WriteAction>
    );
  }
  return null;
}

/** Príchody a odchody recepcie: dnes / zajtra podľa obchodného dňa, offline z trezora (HLD-frontend kap. 3.2). */
export function MovementsPage({ me, renderAssignBed }: { me: Me | null | undefined; renderAssignBed?: RenderAssignBed }) {
  const { t, i18n } = useTranslation();
  const { state } = useConnection();
  const [pickedReception, setPickedReception] = useState<number | undefined>();
  const [offset, setOffset] = useState<0 | 1>(0);
  const [assigning, setAssigning] = useState<Movement | null>(null);
  const status = useStatusActions();

  const receptions = useQuery({ queryKey: ['movements', 'receptions', state], queryFn: loadReceptions });
  const allowed = useMemo(() => {
    const list = (receptions.data?.data ?? []).filter((r) => r.id !== undefined && r.active !== false);
    const ids = receptionsInScope(me, 'PLAN_VIEW', list.map((r) => r.id as number));
    return list.filter((r) => ids.includes(r.id as number));
  }, [receptions.data, me]);
  const receptionId =
    [pickedReception, me?.defaultReceptionId].find((id) => id !== undefined && allowed.some((r) => r.id === id)) ?? allowed[0]?.id;

  const day = useQuery({
    queryKey: ['movements', 'day', receptionId, state],
    queryFn: () => loadBusinessDay(receptionId as number),
    enabled: receptionId !== undefined,
  });
  const today = day.data?.data?.businessDate;
  const date = today ? addDays(today, offset) : undefined;

  const result = useQuery({
    queryKey: ['movements', 'list', receptionId, date, state],
    queryFn: () => loadMovements(receptionId as number, date as string),
    enabled: receptionId !== undefined && date !== undefined,
  });
  const data = result.data;
  const arrivals = useMemo(() => sortMovements(data?.data?.arrivals), [data]);
  const departures = useMemo(() => sortMovements(data?.data?.departures), [data]);
  const noAccess = receptions.data && allowed.length === 0;
  const loading = receptions.isPending || (receptionId !== undefined && (day.isPending || (date !== undefined && result.isPending)));

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="movements-page">
      <style>{PRINT_CSS}</style>
      <Group justify="space-between" mb="sm" align="flex-end">
        <Title order={2}>{t('movements.title')}</Title>
        {data && !data.empty && <DataAge source={data.source} syncedAt={data.syncedAt} />}
      </Group>

      <Group mb="md" gap="md" align="flex-end" wrap="wrap" className="movements-noprint">
        <NativeSelect
          label={t('shell.receptionLabel')}
          data={allowed.map((r) => ({ value: String(r.id), label: r.name ?? r.code ?? String(r.id) }))}
          value={receptionId === undefined ? '' : String(receptionId)}
          onChange={(e) => setPickedReception(Number(e.currentTarget.value))}
        />
        <Button.Group aria-label={t('movements.dayGroup')}>
          <Button variant={offset === 0 ? 'filled' : 'default'} aria-pressed={offset === 0} disabled={!today} onClick={() => setOffset(0)}>
            {t('movements.today')}{today ? `, ${fmtDay(today, i18n.language)}` : ''}
          </Button>
          <Button variant={offset === 1 ? 'filled' : 'default'} aria-pressed={offset === 1} disabled={!today} onClick={() => setOffset(1)}>
            {t('movements.tomorrow')}{today ? `, ${fmtDay(addDays(today, 1), i18n.language)}` : ''}
          </Button>
        </Button.Group>
        <Button variant="default" onClick={() => window.print()} disabled={!data || data.empty}>{t('movements.print')}</Button>
      </Group>

      {status.node}
      {noAccess && <Alert color="orange">{t('plan.noReception')}</Alert>}
      {loading && !noAccess && <Loader size="sm" data-testid="movements-loading" />}
      {(result.isError || day.isError || receptions.isError) && <Alert color="red">{t('errors.generic')}</Alert>}
      {data?.empty && <Alert color="orange" data-testid="movements-not-stored">{t('offline.notStored')}</Alert>}
      {data && !data.empty && date && (
        <>
          <Text size="sm" c="dimmed" mb="sm" data-testid="movements-date">{date}</Text>
          <Group align="flex-start" grow wrap="wrap">
            <MovementList kind="arrivals" rows={arrivals} me={me} actionable={offset === 0} onAction={status.request} onAssign={renderAssignBed ? setAssigning : undefined} />
            <MovementList kind="departures" rows={departures} me={me} actionable={offset === 0} onAction={status.request} />
          </Group>
          {assigning?.reservationId !== undefined &&
            renderAssignBed?.({ id: assigning.reservationId, reservationNumber: assigning.reservationNumber }, () => setAssigning(null))}
        </>
      )}
    </Paper>
  );
}
