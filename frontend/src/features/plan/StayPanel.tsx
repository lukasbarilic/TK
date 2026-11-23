import { Badge, Button, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { originState } from '../../shared/navigation/origin';
import { queryKeys } from '../../shared/api/queryKeys';
import { DataAge } from '../../shared/offline';
import { loadCard } from './planApi';
import type { BarVM } from './types';

interface Props {
  bar: BarVM;
  roomLabel: string;
  bedLabel: string;
  /** Má používateľ `RESERVATION_VIEW` pre recepciu karty? Inak je číslo len text. */
  canOpenCard?: boolean;
  onClose: () => void;
}

/** Bočný panel: pobyt (z pásika) a rezervačná karta (online zo servera, offline z trezora). */
export function StayPanel({ bar, roomLabel, bedLabel, canOpenCard = false, onClose }: Props) {
  const { t } = useTranslation();
  const reservationId = bar.reservationId;
  const card = useQuery({
    queryKey: [...queryKeys.plan, 'card', reservationId],
    queryFn: () => loadCard(reservationId as number),
    enabled: reservationId !== undefined,
  });

  return (
    <Paper component="aside" withBorder p="md" radius="lg" data-testid="stay-panel" aria-label={t('plan.panel.title')} w={320} style={{ flex: 'none' }}>
      <Group justify="space-between" mb="sm" wrap="nowrap">
        <Title order={3} size="h4">
          {bar.type === 'STAY' ? bar.label : t(`plan.panel.type.${bar.type}`)}
        </Title>
        <Button variant="subtle" size="compact-sm" onClick={onClose}>
          {t('plan.panel.close')}
        </Button>
      </Group>
      <Stack gap={4} mb="md">
        <Text size="sm">{t('plan.panel.place', { room: roomLabel, bed: bedLabel })}</Text>
        <Text size="sm" data-testid="panel-dates">
          {bar.from} – {bar.to}
        </Text>
        {bar.status && <Badge variant="light" w="fit-content">{t(`plan.status.${bar.status}`, bar.status)}</Badge>}
        {bar.type === 'ROOM_BLOCK' && bar.reason && <Text size="sm">{bar.reason}</Text>}
        {bar.type === 'HELD_BED' && <Text size="sm" c="dimmed">{t('plan.panel.heldHint')}</Text>}
        {bar.locked && <Text size="sm">{t('plan.panel.locked')}</Text>}
      </Stack>

      {reservationId !== undefined && (
        <Stack gap={4} data-testid="reservation-card">
          <Text fw={600} size="sm">{t('plan.panel.card')}</Text>
          {card.isLoading && <Loader size="xs" />}
          {card.isError && <Text size="sm" c="red">{t('plan.panel.cardError')}</Text>}
          {card.data?.empty && <Text size="sm" c="dimmed">{t('offline.notStored')}</Text>}
          {card.data && !card.data.empty && (
            <>
              {canOpenCard ? (
                <Text size="sm">
                  <Link
                    to={`/reservations/${reservationId}`}
                    state={originState(t('menu.plan'), '/plan')}
                    aria-label={t('plan.panel.openCard', { number: card.data.data.reservationNumber })}
                    data-testid="panel-card-link"
                    style={{ textDecoration: 'underline' }}
                  >
                    {card.data.data.reservationNumber}
                  </Link>
                </Text>
              ) : (
                <Text size="sm">{card.data.data.reservationNumber}</Text>
              )}
              <Text size="sm">
                {card.data.data.arrivalDate} – {card.data.data.departureDate}
              </Text>
              {card.data.data.exclusiveRoomUse && (
                <Badge variant="light" color="orange" w="fit-content" data-testid="panel-exclusive">{t('plan.panel.exclusive')}</Badge>
              )}
              {(card.data.data.guests ?? [])
                .filter((g) => !g.cancelled)
                .map((g) => (
                  <Text size="sm" key={g.id}>
                    {g.lastName} {g.firstName}
                    {g.primaryGuest ? ` (${t('plan.panel.primary')})` : ''}
                  </Text>
                ))}
              {card.data.data.note && (
                <Text size="sm" data-testid="panel-note" style={{ whiteSpace: 'pre-wrap' }}>
                  <b>{t('plan.panel.note')}:</b> {card.data.data.note}
                </Text>
              )}
              <Text size="sm" c="dimmed" data-testid="panel-comment-count">
                {t('plan.panel.comments', { count: bar.commentCount ?? card.data.data.comments?.length ?? 0 })}
              </Text>
              <DataAge source={card.data.source} syncedAt={card.data.syncedAt} />
            </>
          )}
        </Stack>
      )}
    </Paper>
  );
}
