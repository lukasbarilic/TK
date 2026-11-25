import { Alert, Avatar, Button, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { WriteAction } from '../../shared/offline';
import { diffDays } from '../../shared/time';
import { activeGuests, primaryOf } from './ReservationHeader';
import { loadStays, type ReservationDetail } from './reservationsApi';

// Sekundárny text s kontrastom ≥ 4,5:1 na bielom pozadí (Mantine „dimmed“ je svetlejší).
const MUTED = '#4A5D75';

const fmtTime = (iso: string | undefined, lang: string) =>
  iso ? new Date(iso).toLocaleString(lang, { day: 'numeric', month: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : undefined;

const nightsOf = (from?: string, to?: string): number | undefined => {
  if (!from || !to) return undefined;
  const n = diffDays(from, to);
  return Number.isNaN(n) ? undefined : n;
};

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Stack gap={6} component="section" aria-label={title}>
      <Title order={2} size="sm" c={MUTED} tt="uppercase">{title}</Title>
      {children}
    </Stack>
  );
}

/** Bočný panel „kto, kde, kedy“: hostia, termín, ubytovanie a priebeh stavu. */
export function ReservationSidePanel({
  d,
  canSeeContacts,
  renderCheckIn,
}: {
  d: ReservationDetail;
  canSeeContacts: boolean;
  renderCheckIn?: (card: ReservationDetail, onClose: () => void) => ReactNode;
}) {
  const { t, i18n } = useTranslation();
  const stays = useQuery({ queryKey: ['reservations', 'stays', d.id], queryFn: () => loadStays(d.id as number), retry: false });
  const [checkingIn, setCheckingIn] = useState(false);
  const guests = activeGuests(d);
  const main = primaryOf(d);
  const others = guests.filter((g) => g !== main);
  const nights = nightsOf(d.arrivalDate, d.departureDate);
  const rank = { RESERVED: 0, NO_SHOW: 0, CANCELLED: 0, CHECKED_IN: 1, CHECKED_OUT: 2 }[d.status ?? 'RESERVED'];
  const steps = [
    { key: 'RESERVED', time: undefined },
    { key: 'CHECKED_IN', time: fmtTime(d.checkedInAt, i18n.language) },
    { key: 'CHECKED_OUT', time: fmtTime(d.checkedOutAt, i18n.language) },
  ];
  const ended = d.status === 'CANCELLED' || d.status === 'NO_SHOW';

  const guestRow = (g: (typeof guests)[number], isMain: boolean) => (
    <Group key={g.id} gap="sm" wrap="nowrap" align="flex-start" data-testid="guest-row">
      <Avatar color={isMain ? 'blue' : 'gray'} variant={isMain ? 'filled' : 'light'} radius="xl" aria-hidden="true">
        {`${g.firstName?.[0] ?? ''}${g.lastName?.[0] ?? ''}`.toUpperCase()}
      </Avatar>
      <Stack gap={0}>
        <Text size="sm" fw={isMain ? 700 : 400}>{g.lastName} {g.firstName}</Text>
        {isMain && <Text size="xs" c={MUTED}>{t('reservations.mainGuest')}</Text>}
        {canSeeContacts && (g.email || g.phone) && (
          <Text size="xs" c={MUTED} data-testid="guest-contact">{[g.email, g.phone].filter(Boolean).join(', ')}</Text>
        )}
      </Stack>
    </Group>
  );

  return (
    <Paper withBorder p="md" radius="md" bg="gray.0" data-testid="reservation-side-panel" component="aside" aria-label={t('reservations.sidePanel')}>
      <Stack gap="lg">
        <Section title={t('reservations.guests')}>
          {guests.length === 0 && <Text size="sm" c={MUTED}>{t('reservations.noGuests')}</Text>}
          {main && guestRow(main, true)}
          {others.map((g) => guestRow(g, false))}
        </Section>

        <Section title={t('reservations.term')}>
          <Text size="sm" data-testid="reservation-dates">{d.arrivalDate} – {d.departureDate}</Text>
          {nights !== undefined && <Text size="xs" c={MUTED} data-testid="reservation-nights">{t('stays.nights', { count: nights })}</Text>}
        </Section>

        <Section title={t('reservations.accommodation')}>
          {renderCheckIn && (d.status === 'RESERVED' || d.status === 'CHECKED_IN') && (
            <WriteAction>
              <Button size="compact-md" variant="light" onClick={() => setCheckingIn(true)}>{t('stays.open')}</Button>
            </WriteAction>
          )}
          {checkingIn && renderCheckIn?.(d, () => setCheckingIn(false))}
          {stays.isPending && <Loader size="xs" />}
          {stays.isError && <Text size="sm" c={MUTED} data-testid="stays-unavailable">{t('offline.onlineOnly')}</Text>}
          {stays.data && stays.data.length === 0 && <Text size="sm" c={MUTED}>{t('reservations.noStays')}</Text>}
          {stays.data?.map((s) => (
            <Stack key={s.id} gap={0} data-testid="stay-row">
              <Text size="sm">{s.buildingName} {s.roomNumber}, {t('plan.bed', { n: s.bedNumber })}</Text>
              <Text size="xs" c={MUTED}>{s.dateFrom} – {s.dateTo}</Text>
              <Link to={`/plan?roomId=${s.roomId}&date=${s.dateFrom}`} style={{ fontSize: 14 }}>{t('reservations.showInPlan')}</Link>
            </Stack>
          ))}
        </Section>

        <Section title={t('reservations.progress')}>
          <Stack gap={4} data-testid="status-steps" component="ol" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {steps.map((s, i) => (
              <li key={s.key} data-testid="status-step" data-done={i <= rank && !ended} aria-current={i === rank && d.status !== 'CANCELLED' ? 'step' : undefined}>
                <Text size="sm" fw={i === rank ? 700 : 400} c={i <= rank && d.status !== 'CANCELLED' ? undefined : MUTED}>
                  {i + 1}. {t(`status.steps.${s.key}`)}
                </Text>
                {s.time && <Text size="xs" c={MUTED}>{s.time}</Text>}
              </li>
            ))}
          </Stack>
          {d.status === 'CANCELLED' && (
            <Alert color="red" p="xs" data-testid="status-cancelled">
              {t('status.cancelled', { time: fmtTime(d.cancelledAt, i18n.language) ?? '' })}
              {d.cancelReason ? ` ${t('status.cancelReason', { reason: d.cancelReason })}` : ''}
            </Alert>
          )}
        </Section>
      </Stack>
    </Paper>
  );
}
