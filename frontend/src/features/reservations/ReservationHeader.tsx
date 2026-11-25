import { ActionIcon, Anchor, Badge, Breadcrumbs, Group, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import type { Crumb } from '../../shared/ui/PageHeader';
import type { ReservationDetail } from './reservationsApi';

export const activeGuests = (d: ReservationDetail) => (d.guests ?? []).filter((g) => !g.cancelled);
export const primaryOf = (d: ReservationDetail) => {
  const guests = activeGuests(d);
  return guests.find((g) => g.primaryGuest) ?? guests[0];
};

/** Hlavička detailu v jednom riadku: Späť, meno hlavného hosťa (+N), číslo, stav a akcie (`children`); na úzkej šírke sa zalamuje. */
export function ReservationHeader({ d, crumbs, fallback, children }: { d?: ReservationDetail; crumbs: Crumb[]; fallback: string; children?: React.ReactNode }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const back = () => void (location.key !== 'default' ? navigate(-1) : navigate(fallback, { replace: true }));
  const main = d ? primaryOf(d) : undefined;
  const more = d ? activeGuests(d).length - (main ? 1 : 0) : 0;
  const title = main ? `${main.lastName} ${main.firstName}` : (d?.reservationNumber ?? t('reservations.detail'));
  return (
    <div data-testid="page-header">
      <Breadcrumbs aria-label={t('shared.breadcrumbs')} mb={4}>
        {crumbs.map((c, i) =>
          c.to ? (
            <Anchor key={i} component={Link} to={c.to} size="sm">{c.label}</Anchor>
          ) : (
            <span key={i} style={{ fontSize: 14 }} aria-current="page">{c.label}</span>
          ),
        )}
      </Breadcrumbs>
      <Group mb="md" gap="sm" align="center" justify="space-between" data-testid="reservation-header">
        <Group gap="sm" align="center">
          <ActionIcon variant="default" size="lg" aria-label={t('shared.back')} onClick={back}>
            <span aria-hidden="true">←</span>
          </ActionIcon>
          <Title order={1} size="h2" data-testid={!d ? undefined : main ? 'reservation-title' : 'reservation-number'}>
            {title}
            {more > 0 && <span data-testid="more-guests"> +{more}</span>}
          </Title>
          {main && d?.reservationNumber && <Text fw={600} data-testid="reservation-number">{d.reservationNumber}</Text>}
          {d?.status && <Badge variant="light" size="lg">{t(`plan.status.${d.status}`, d.status)}</Badge>}
          {d?.exclusiveRoomUse && <Badge variant="light" color="orange" data-testid="exclusive-badge">{t('reservations.exclusiveRoomUse')}</Badge>}
        </Group>
        {children}
      </Group>
    </div>
  );
}
