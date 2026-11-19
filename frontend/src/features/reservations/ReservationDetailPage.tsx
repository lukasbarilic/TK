import { Alert, Badge, Button, Checkbox, Group, Loader, Paper, Stack, Table, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation } from 'react-router-dom';
import { readOrigin } from '../../shared/navigation/origin';
import { PageHeader } from '../../shared/ui/PageHeader';
import { ApiError } from '../../shared/api/errors';
import { DataAge, WriteAction } from '../../shared/offline';
import { putLocal } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { loadReservation, loadStays, updateReservation, type ReservationDetail } from './reservationsApi';

/** Detail karty: hostia, kontakty (len s `RESERVATION_VIEW`, rieši rodič), pobyty s odkazom na plán, úprava online. */
export function ReservationDetailPage({
  id,
  canSeeContacts,
  renderCheckIn,
}: {
  id: number;
  canSeeContacts: boolean;
  /** Dialóg ubytovania dodáva `app/` (features sa medzi sebou neimportujú); bez neho sa tlačidlo nezobrazí. */
  renderCheckIn?: (card: ReservationDetail, onClose: () => void) => ReactNode;
}) {
  const { t } = useTranslation();
  const card = useQuery({ queryKey: ['reservations', 'detail', id], queryFn: () => loadReservation(id) });
  const stays = useQuery({ queryKey: ['reservations', 'stays', id], queryFn: () => loadStays(id), retry: false });
  const [checkingIn, setCheckingIn] = useState(false);
  const origin = readOrigin(useLocation().state);

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="reservation-detail">
      <PageHeader
        title={t('reservations.detail')}
        fallback="/reservations"
        crumbs={[
          origin ?? { label: t('menu.reservations'), to: '/reservations' },
          { label: card.data && !card.data.empty ? (card.data.data.reservationNumber ?? t('reservations.detail')) : t('reservations.detail') },
        ]}
      />
      {card.isPending && <Loader size="sm" />}
      {card.isError && <Alert color="red">{t('errors.generic')}</Alert>}
      {card.data?.empty && <Text c="dimmed" data-testid="reservation-not-stored">{t('offline.notStored')}</Text>}
      {card.data && !card.data.empty && (
        <Stack gap="md">
          <DetailBody d={card.data.data} />
          <DataAge source={card.data.source} syncedAt={card.data.syncedAt} />
          <Stack gap={4}>
            <Text fw={600}>{t('reservations.guests')}</Text>
            {(card.data.data.guests ?? []).filter((g) => !g.cancelled).map((g) => (
              <Group key={g.id} gap="md" data-testid="guest-row">
                <Text size="sm">
                  {g.lastName} {g.firstName}
                  {g.primaryGuest ? ` (${t('plan.panel.primary')})` : ''}
                </Text>
                {canSeeContacts && (g.email || g.phone) && (
                  <Text size="sm" c="dimmed" data-testid="guest-contact">{[g.email, g.phone].filter(Boolean).join(', ')}</Text>
                )}
              </Group>
            ))}
          </Stack>
          <Stack gap={4}>
            <Group justify="space-between">
              <Text fw={600}>{t('reservations.stays')}</Text>
              {renderCheckIn && (card.data.data.status === 'RESERVED' || card.data.data.status === 'CHECKED_IN') && (
                <WriteAction>
                  <Button size="compact-md" onClick={() => setCheckingIn(true)}>{t('stays.open')}</Button>
                </WriteAction>
              )}
            </Group>
            {checkingIn && renderCheckIn?.(card.data.data, () => setCheckingIn(false))}
            {stays.isPending && <Loader size="xs" />}
            {stays.isError && <Text size="sm" c="dimmed" data-testid="stays-unavailable">{t('offline.onlineOnly')}</Text>}
            {stays.data && stays.data.length === 0 && <Text size="sm" c="dimmed">{t('reservations.noStays')}</Text>}
            {stays.data && stays.data.length > 0 && (
              <Table>
                <Table.Tbody>
                  {stays.data.map((s) => (
                    <Table.Tr key={s.id} data-testid="stay-row">
                      <Table.Td>{s.buildingName} {s.roomNumber}, {t('plan.bed', { n: s.bedNumber })}</Table.Td>
                      <Table.Td>{s.dateFrom} – {s.dateTo}</Table.Td>
                      <Table.Td>
                        <Link to={`/plan?roomId=${s.roomId}&date=${s.dateFrom}`}>{t('reservations.showInPlan')}</Link>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            )}
          </Stack>
          <EditForm key={card.data.data.version} d={card.data.data} />
        </Stack>
      )}
    </Paper>
  );
}

function DetailBody({ d }: { d: ReservationDetail }) {
  const { t } = useTranslation();
  return (
    <Stack gap={2}>
      <Group gap="sm">
        <Text fw={700} data-testid="reservation-number">{d.reservationNumber}</Text>
        {d.status && <Badge variant="light">{t(`plan.status.${d.status}`, d.status)}</Badge>}
      </Group>
      <Text size="sm" data-testid="reservation-dates">{d.arrivalDate} – {d.departureDate}</Text>
    </Stack>
  );
}

/** Úprava termínu a príznakov; `version` ide v tele (optimistic locking), konflikt = výzva na obnovenie. */
function EditForm({ d }: { d: ReservationDetail }) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [arrival, setArrival] = useState(d.arrivalDate ?? '');
  const [departure, setDeparture] = useState(d.departureDate ?? '');
  const [doNotMove, setDoNotMove] = useState(d.doNotMove === true);
  const [conflict, setConflict] = useState(false);

  const save = useMutation({
    mutationFn: () =>
      updateReservation(d.id as number, { version: d.version as number, arrivalDate: arrival, departureDate: departure, doNotMove }),
    onSuccess: async (saved) => {
      setConflict(false);
      await putLocal(DS.reservation, String(d.id), saved);
      await client.invalidateQueries({ queryKey: ['reservations'] });
      await client.invalidateQueries({ queryKey: ['plan'] });
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        setConflict(true);
        void client.invalidateQueries({ queryKey: ['reservations', 'detail', d.id] });
      }
    },
  });

  return (
    <Stack gap="xs" data-testid="reservation-edit">
      <Text fw={600}>{t('reservations.edit')}</Text>
      <Group align="flex-end">
        <TextInput type="date" label={t('reservations.arrival')} value={arrival} onChange={(e) => setArrival(e.currentTarget.value)} />
        <TextInput type="date" label={t('reservations.departure')} value={departure} onChange={(e) => setDeparture(e.currentTarget.value)} />
        <Checkbox label={t('reservations.doNotMove')} checked={doNotMove} onChange={(e) => setDoNotMove(e.currentTarget.checked)} />
        <WriteAction>
          <Button onClick={() => save.mutate()} loading={save.isPending}>{t('reservations.save')}</Button>
        </WriteAction>
      </Group>
      {conflict && <Alert color="orange" data-testid="version-conflict">{t('reservations.conflict')}</Alert>}
      {save.isError && !conflict && <Alert color="red">{t('errors.generic')}</Alert>}
      {save.isSuccess && <Text size="sm" c="green">{t('reservations.saved')}</Text>}
    </Stack>
  );
}
