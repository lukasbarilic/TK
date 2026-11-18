import { Alert, Badge, Button, Group, Loader, NativeSelect, Paper, Table, Text, TextInput, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { flushSync } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { isNetError } from '../../shared/api/errors';
import type { components } from '../../shared/api/schema';
import { DataAge, useConnection } from '../../shared/offline';
import { hasPermissionCode } from '../../shared/offline/scope';
import {
  buildGuestCsv, downloadGuestCsv, filterGuests, journalOfflineExport, loadBuildings, loadBusinessDay, loadGuestList, saveCsv,
} from './guestListApi';

type Me = components['schemas']['MeView'];

const PRINT_CSS = `.guest-print-head { display: none; }
@media print {
  body * { visibility: hidden; }
  [data-testid="guest-list-page"], [data-testid="guest-list-page"] * { visibility: visible; }
  [data-testid="guest-list-page"] { position: absolute; left: 0; top: 0; width: 100%; border: 0; }
  .guest-print-head { display: block; }
  .guest-noprint { display: none !important; }
}`;

const fmt = (iso?: string | null) => (iso ? iso.split('-').reverse().join('. ') : '');

/** Zoznam hostí pre lekára (HLD-ubytovanie kap. 8.5): deň, budova, lekár, tlač a CSV, offline z trezora. */
export function GuestListPage({ me }: { me: Me | null | undefined }) {
  const { t, i18n } = useTranslation();
  const { state } = useConnection();
  const canViewAll = hasPermissionCode(me, 'GUEST_LOCATION_VIEW_ALL');
  const canExport = hasPermissionCode(me, 'GUEST_LOCATION_EXPORT');
  const [pickedDate, setPickedDate] = useState('');
  const [building, setBuilding] = useState('');
  const [doctor, setDoctor] = useState('');
  const [printedAt, setPrintedAt] = useState('');
  const [message, setMessage] = useState<{ color: string; text: string } | null>(null);

  const reception = me?.defaultReceptionId;
  const businessDay = useQuery({
    queryKey: ['guest-list', 'day', reception, state],
    queryFn: () => loadBusinessDay(reception as number),
    enabled: reception !== undefined,
  });
  const date = pickedDate || businessDay.data?.data?.businessDate || '';

  const list = useQuery({
    queryKey: ['guest-list', 'list', date, state],
    queryFn: () => loadGuestList(date),
    enabled: date !== '',
  });
  const buildings = useQuery({ queryKey: ['guest-list', 'buildings', state], queryFn: loadBuildings });
  const data = list.data;
  const all = useMemo(() => data?.data?.guests ?? [], [data]);
  const guests = useMemo(() => filterGuests(all, building, canViewAll ? doctor : ''), [all, building, doctor, canViewAll]);
  const doctors = useMemo(() => {
    const m = new Map<number, string>();
    for (const g of all) if (g.doctorId != null) m.set(g.doctorId, g.doctorName ?? String(g.doctorId));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1], 'sk'));
  }, [all]);
  const buildingList = (buildings.data?.data ?? []).filter((b) => b.name && b.active !== false);
  const doctorLabel = canViewAll ? (doctor ? doctors.find(([id]) => String(id) === doctor)?.[1] : t('guestList.allDoctors')) : me?.displayName;
  const loading = reception !== undefined && businessDay.isPending && !pickedDate;
  const waitingForDay = date === '' && !loading;

  const print = () => {
    flushSync(() => setPrintedAt(new Date().toLocaleString(i18n.language)));
    window.print();
  };

  const exportCsv = async () => {
    setMessage(null);
    const fileName = `zoznam-hosti-${date}.csv`;
    try {
      let bytes: Uint8Array | undefined;
      if (state === 'online') {
        try {
          const buildingId = buildingList.find((b) => b.name === building)?.id;
          bytes = await downloadGuestCsv(date, building ? buildingId : undefined, canViewAll && doctor ? Number(doctor) : undefined);
        } catch (e) {
          if (!isNetError(e)) throw e;
        }
      }
      if (!bytes) {
        bytes = buildGuestCsv(guests);
        await journalOfflineExport(me?.username ?? '', [...new Set(guests.map((g) => g.reservationId))]);
      }
      if (await saveCsv(fileName, bytes)) setMessage({ color: 'teal', text: t('guestList.saved') });
    } catch {
      setMessage({ color: 'red', text: t('errors.generic') });
    }
  };

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="guest-list-page">
      <style>{PRINT_CSS}</style>
      <Group justify="space-between" mb="sm" align="flex-end">
        <Title order={2}>{t('guestList.title')}</Title>
        {data && !data.empty && <DataAge source={data.source} syncedAt={data.syncedAt} />}
      </Group>

      <div className="guest-print-head" data-testid="guest-print-head">
        <Text fw={600}>{t('guestList.printDate', { date: fmt(date) })}</Text>
        <Text>{t('guestList.printDoctor', { doctor: doctorLabel })}</Text>
        <Text>{t('guestList.printedAt', { time: printedAt })}</Text>
      </div>

      <Group mb="md" gap="md" align="flex-end" wrap="wrap" className="guest-noprint">
        <TextInput
          type="date"
          label={t('guestList.day')}
          value={date}
          onChange={(e) => setPickedDate(e.currentTarget.value)}
        />
        <NativeSelect
          label={t('guestList.building')}
          data={[{ value: '', label: t('guestList.allBuildings') }, ...buildingList.map((b) => ({ value: b.name as string, label: b.name as string }))]}
          value={building}
          onChange={(e) => setBuilding(e.currentTarget.value)}
        />
        {canViewAll && (
          <NativeSelect
            label={t('guestList.doctor')}
            data={[{ value: '', label: t('guestList.allDoctors') }, ...doctors.map(([id, name]) => ({ value: String(id), label: name }))]}
            value={doctor}
            onChange={(e) => setDoctor(e.currentTarget.value)}
          />
        )}
        {data && !data.empty && <Badge size="lg" variant="light" data-testid="guest-count">{t('guestList.count', { count: guests.length })}</Badge>}
        {canExport && (
          <>
            <Button variant="default" onClick={print} disabled={!data || data.empty}>{t('guestList.print')}</Button>
            <Button onClick={exportCsv} disabled={!data || data.empty}>{t('guestList.exportCsv')}</Button>
          </>
        )}
      </Group>

      {message && <Alert color={message.color} mb="sm" data-testid="guest-message">{message.text}</Alert>}
      {(loading || (list.isPending && date !== '')) && <Loader size="sm" data-testid="guest-loading" />}
      {waitingForDay && <Alert color="orange">{t('guestList.pickDay')}</Alert>}
      {(list.isError || businessDay.isError) && <Alert color="red">{t('errors.generic')}</Alert>}
      {data?.empty && <Alert color="orange" data-testid="guest-not-stored">{t('offline.notStored')}</Alert>}
      {data && !data.empty &&
        (guests.length === 0 ? (
          <Text c="dimmed" data-testid="guest-empty">{t('guestList.empty')}</Text>
        ) : (
          <Table highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('guestList.guest')}</Table.Th>
                <Table.Th>{t('guestList.birthYear')}</Table.Th>
                <Table.Th>{t('guestList.buildingCol')}</Table.Th>
                <Table.Th>{t('guestList.floor')}</Table.Th>
                <Table.Th>{t('guestList.room')}</Table.Th>
                <Table.Th>{t('guestList.stay')}</Table.Th>
                <Table.Th>{t('guestList.status')}</Table.Th>
                {canViewAll && <Table.Th>{t('guestList.doctor')}</Table.Th>}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {guests.map((g, i) => (
                <Table.Tr
                  key={`${g.reservationId}-${g.room ?? ''}-${g.bedNumber ?? ''}-${i}`}
                  data-testid="guest-row"
                  data-moved={g.movedOn ? 'true' : 'false'}
                  style={g.movedOn ? { background: '#FFF8EF' } : undefined}
                >
                  <Table.Td>
                    <Text fw={600} size="sm">{g.guestName}</Text>
                    {g.movedOn && (
                      <Text size="xs" c="#7A3705" data-testid="guest-moved">
                        {t('guestList.moved', { from: fmt(g.stayFrom), to: fmt(g.stayTo) })}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>{g.birthYear}</Table.Td>
                  <Table.Td>{g.building}</Table.Td>
                  <Table.Td>{g.floor}</Table.Td>
                  <Table.Td fw={600}>{g.room ? `${g.room}${g.bedNumber ? ` / ${g.bedNumber}` : ''}` : t('guestList.noRoom')}</Table.Td>
                  <Table.Td>{fmt(g.arrivalDate)} – {fmt(g.departureDate)}</Table.Td>
                  <Table.Td>{g.status && <Badge variant="light">{t(`plan.status.${g.status}`, g.status)}</Badge>}</Table.Td>
                  {canViewAll && <Table.Td>{g.doctorName}</Table.Td>}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        ))}
    </Paper>
  );
}
