import { Alert, Badge, Button, Group, Loader, Modal, Radio, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorText } from '../../shared/api/errorText';
import { ApiError } from '../../shared/api/errors';
import type { components } from '../../shared/api/schema';
import { WriteAction } from '../../shared/offline';
import { diffDays } from '../../shared/time';
import { ConfirmWarningsDialog } from '../../shared/ui/ConfirmWarningsDialog';
import { refreshAfterStay } from './refresh';
import { createStay, loadAvailableRooms, searchCardsWithoutStay, type AvailableRoom, type CardListItem } from './staysApi';

type Me = components['schemas']['MeView'];

/** Karta, ktorú ubytúvame. */
export interface CheckInCard {
  id: number;
  reservationNumber?: string;
  arrivalDate?: string;
  departureDate?: string;
  bookedCategoryId?: number;
}

/** Voľné lôžko a deň vybrané v pláne (UC2). */
export interface CheckInTarget {
  receptionId?: number;
  roomId: number;
  bedId: number;
  roomLabel: string;
  bedLabel: string;
  day: string;
}

interface Props {
  me: Me | null | undefined;
  onClose: () => void;
  /** Z detailu karty: výber voľnej izby a lôžka. */
  card?: CheckInCard;
  /** Z plánu: výber karty pre dané lôžko a deň. */
  target?: CheckInTarget;
}

const UNLIMITED_NIGHTS = 365;

/** Limit nocí z oprávnenia `STAY_CREATE` (najvyšší z rolí); rozhoduje server, UI len nepustí zjavne dlhší pobyt. */
export function nightsLimit(me: Me | null | undefined): number {
  const limits = (me?.permissions ?? []).filter((p) => p.code === 'STAY_CREATE').map((p) => p.limit ?? UNLIMITED_NIGHTS);
  return limits.length ? Math.max(...limits) : UNLIMITED_NIGHTS;
}

const nightsOf = (from: string, to: string): number => {
  try {
    return diffDays(from, to);
  } catch {
    return 0;
  }
};

/** Ubytovanie klienta z karty (UC1) alebo z plánu (UC2): výber lôžka/karty, limit nocí, potvrdenie upozornení. */
export function CheckInDialog({ me, onClose, card, target }: Props) {
  const { t } = useTranslation();
  const [chosen, setChosen] = useState<CheckInCard | undefined>(card);
  return (
    <Modal opened onClose={onClose} size="lg" title={t('stays.title')} data-testid="checkin-dialog">
      {!chosen && target ? (
        <CardPicker target={target} onPick={(c) => setChosen({ ...c, id: c.id as number })} />
      ) : chosen ? (
        <Form key={chosen.id} me={me} card={chosen} target={target} onClose={onClose} />
      ) : null}
    </Modal>
  );
}

function CardPicker({ target, onPick }: { target: CheckInTarget; onPick: (c: CardListItem) => void }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const [term, setTerm] = useState('');
  const cards = useQuery({ queryKey: ['stays', 'cards', term, target.day], queryFn: () => searchCardsWithoutStay(term, target.day) });
  return (
    <Stack gap="sm">
      <Text size="sm" data-testid="checkin-target">
        {t('stays.target', { place: `${target.roomLabel}, ${t('plan.bed', { n: target.bedLabel })}`, day: target.day })}
      </Text>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setTerm(q);
        }}
      >
        <Group align="flex-end">
          <TextInput label={t('stays.searchCard')} value={q} onChange={(e) => setQ(e.currentTarget.value)} style={{ flex: 1 }} />
          <Button type="submit">{t('reservations.search')}</Button>
        </Group>
      </form>
      {cards.isPending && <Loader size="sm" />}
      {cards.isError && <Alert color="red">{errorText(t, cards.error)}</Alert>}
      {cards.data?.length === 0 && <Text size="sm" c="dimmed">{t('stays.noCards')}</Text>}
      {cards.data?.map((c) => (
        <Group key={c.id} justify="space-between" data-testid="checkin-card">
          <Text size="sm">
            {c.reservationNumber} · {c.guestLastName} {c.guestFirstName} · {c.arrivalDate} – {c.departureDate}
          </Text>
          <Button size="compact-sm" variant="light" onClick={() => onPick(c)}>{t('stays.pick')}</Button>
        </Group>
      ))}
    </Stack>
  );
}

function Form({ me, card, target, onClose }: { me: Me | null | undefined; card: CheckInCard; target?: CheckInTarget; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [from, setFrom] = useState(target?.day ?? card.arrivalDate ?? '');
  const [to, setTo] = useState(card.departureDate ?? '');
  const [allCategories, setAllCategories] = useState(false);
  const [bedId, setBedId] = useState<number | undefined>(target?.bedId);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [error, setError] = useState<string>();

  const canOtherCategory = (me?.permissions ?? []).some((p) => p.code === 'STAY_OTHER_CATEGORY');
  const limit = nightsLimit(me);
  const nights = nightsOf(from, to);
  const tooLong = nights > limit;
  const validDates = nights > 0;

  const rooms = useQuery({
    queryKey: ['stays', 'available', from, to, card.bookedCategoryId, allCategories],
    queryFn: () => loadAvailableRooms({ from, to, categoryId: card.bookedCategoryId, allCategories }),
    enabled: !target && validDates,
  });
  const room = rooms.data?.find((r) => r.freeBeds?.some((b) => b.id === bedId));

  const save = useMutation({
    mutationFn: (confirm: string[]) =>
      createStay(card.id, { bedId: bedId as number, from, to, ...(confirm.length ? { confirm } : {}) }),
    onSuccess: async (res) => {
      setWarnings([]);
      await refreshAfterStay(qc, {
        receptionId: target?.receptionId ?? room?.receptionId,
        roomId: res.stay.roomId ?? target?.roomId,
        from,
        to,
        auditEventId: res.auditEventId,
      });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409 && e.confirmable) {
        setWarnings(e.warnings);
        return;
      }
      setWarnings([]);
      setError(errorText(t, e));
      if (e instanceof ApiError && e.status === 409) {
        // obsadené lôžko / konflikt: obnoviť voľné izby a plán, mutáciu nezopakovať
        setBedId(target?.bedId);
        void qc.invalidateQueries({ queryKey: ['stays', 'available'] });
        void qc.invalidateQueries({ queryKey: ['plan'] });
      }
    },
  });

  const submit = (confirm: string[]) => {
    setError(undefined);
    save.mutate(confirm);
  };

  return (
    <Stack gap="sm">
      <Group gap="xs">
        <Text fw={700}>{card.reservationNumber}</Text>
        {card.arrivalDate && <Text size="sm" c="dimmed">{t('stays.cardTerm', { from: card.arrivalDate, to: card.departureDate })}</Text>}
      </Group>
      {target && (
        <Text size="sm" data-testid="checkin-target">
          {t('stays.target', { place: `${target.roomLabel}, ${t('plan.bed', { n: target.bedLabel })}`, day: target.day })}
        </Text>
      )}
      <Group align="flex-end">
        <TextInput type="date" label={t('stays.from')} value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
        <TextInput type="date" label={t('stays.to')} value={to} onChange={(e) => setTo(e.currentTarget.value)} />
        <Badge variant="light" data-testid="checkin-nights">{t('stays.nights', { count: validDates ? nights : 0 })}</Badge>
      </Group>
      {limit < UNLIMITED_NIGHTS && <Text size="sm" c="dimmed" data-testid="checkin-limit">{t('stays.limit', { count: limit })}</Text>}
      {tooLong && <Alert color="orange" data-testid="checkin-too-long">{t('stays.tooLong', { count: limit })}</Alert>}

      {!target && (
        <Stack gap="xs">
          {canOtherCategory && (
            <Switch label={t('stays.allCategories')} checked={allCategories} onChange={(e) => setAllCategories(e.currentTarget.checked)} />
          )}
          {!validDates && <Text size="sm" c="dimmed">{t('stays.badDates')}</Text>}
          {rooms.isFetching && <Loader size="sm" />}
          {rooms.isError && <Alert color="red">{errorText(t, rooms.error)}</Alert>}
          {rooms.data?.length === 0 && <Text size="sm" c="dimmed" data-testid="checkin-no-rooms">{t('stays.noRooms')}</Text>}
          <Radio.Group value={bedId === undefined ? '' : String(bedId)} onChange={(v) => setBedId(Number(v))} aria-label={t('stays.pickBed')}>
            <Stack gap="xs">
              {rooms.data?.map((r) => <RoomOption key={r.roomId} room={r} />)}
            </Stack>
          </Radio.Group>
        </Stack>
      )}

      {error && <Alert color="red" data-testid="checkin-error">{error}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>{t('confirm.cancel')}</Button>
        <WriteAction>
          <Button onClick={() => submit([])} loading={save.isPending && warnings.length === 0} disabled={bedId === undefined || !validDates || tooLong}>
            {t('stays.submit')}
          </Button>
        </WriteAction>
      </Group>
      <ConfirmWarningsDialog warnings={warnings} loading={save.isPending} onConfirm={() => submit(warnings)} onCancel={() => setWarnings([])} />
    </Stack>
  );
}

function RoomOption({ room }: { room: AvailableRoom }) {
  const { t } = useTranslation();
  return (
    <Stack gap={2} data-testid="checkin-room">
      <Text size="sm" fw={600}>
        {room.buildingName} {room.roomNumber} · {room.categoryName}
        {room.matchesCategory === false ? ` (${t('stays.otherCategory')})` : ''}
      </Text>
      <Group gap="md" pl="sm">
        {(room.freeBeds ?? []).map((b) => (
          <Radio
            key={b.id}
            value={String(b.id)}
            label={b.type === 'EXTRA' ? t('plan.extraBed', { n: b.bedNumber }) : t('plan.bed', { n: b.bedNumber })}
          />
        ))}
      </Group>
    </Stack>
  );
}
