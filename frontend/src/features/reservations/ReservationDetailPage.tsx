import { Alert, Button, Checkbox, Grid, Group, Loader, Modal, Paper, Stack, Text, Textarea } from '@mantine/core';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router-dom';
import { readOrigin } from '../../shared/navigation/origin';
import { ReservationHeader } from './ReservationHeader';
import { ReservationSidePanel } from './ReservationSidePanel';
import { ApiError } from '../../shared/api/errors';
import { DataAge, WriteAction } from '../../shared/offline';
import { putLocal } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { hasPermissionCode } from '../../shared/offline/scope';
import type { components } from '../../shared/api/schema';
import { StatusButtons, useStatusActions } from '../../shared/status';
import { addComment, hideComment, loadReservation, updateReservation, type ReservationComment, type ReservationDetail } from './reservationsApi';

/** Detail karty: hlavička s hlavným hosťom a akciami, bočný panel (hostia, termín, ubytovanie, priebeh), poznámka, úprava online a komentáre. */
export function ReservationDetailPage({
  id,
  canSeeContacts,
  renderCheckIn,
  me,
}: {
  id: number;
  canSeeContacts: boolean;
  /** Prihlásený používateľ: podľa oprávnení `RESERVATION_STATUS(_FIX)` sa ponúknu akcie stavu. */
  me?: components['schemas']['MeView'] | null;
  /** Dialóg ubytovania dodáva `app/` (features sa medzi sebou neimportujú); bez neho sa tlačidlo nezobrazí. */
  renderCheckIn?: (card: ReservationDetail, onClose: () => void) => ReactNode;
}) {
  const { t } = useTranslation();
  const card = useQuery({ queryKey: ['reservations', 'detail', id], queryFn: () => loadReservation(id) });
  const status = useStatusActions();
  const origin = readOrigin(useLocation().state);

  const d = card.data && !card.data.empty ? card.data.data : undefined;
  const canEdit = hasPermissionCode(me, 'RESERVATION_EDIT');

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="reservation-detail">
      <ReservationHeader
        d={d}
        fallback="/reservations"
        crumbs={[
          origin ?? { label: t('menu.reservations'), to: '/reservations' },
          { label: d?.reservationNumber ?? t('reservations.detail') },
        ]}
      >
        {d && <StatusButtons primaryLast status={d.status} me={me} busy={status.busy} onAction={(a) => status.request(a, id)} />}
      </ReservationHeader>
      {status.node}
      {card.isPending && <Loader size="sm" />}
      {card.isError && <Alert color="red">{t('errors.generic')}</Alert>}
      {card.data?.empty && <Text c="dimmed" data-testid="reservation-not-stored">{t('offline.notStored')}</Text>}
      {card.data && !card.data.empty && d && (
        <Grid gap="lg">
          <Grid.Col span={{ base: 12, md: 4 }}>
            <ReservationSidePanel d={d} canSeeContacts={canSeeContacts} canEdit={canEdit} renderCheckIn={renderCheckIn} />
          </Grid.Col>
          <Grid.Col span={{ base: 12, md: 8 }}>
            <Stack gap="md">
              <DataAge source={card.data.source} syncedAt={card.data.syncedAt} />
              <NotePanel d={d} canEdit={canEdit} />
              <CommentsPanel d={d} canEdit={canEdit} />
            </Stack>
          </Grid.Col>
        </Grid>
      )}
    </Paper>
  );
}

const fmtTime = (iso: string | undefined, lang: string) =>
  iso ? new Date(iso).toLocaleString(lang, { day: 'numeric', month: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : undefined;

/** Žltý panel poznámky karty; úprava len s `RESERVATION_EDIT` a len online (`version` ide v tele PATCH). */
function NotePanel({ d, canEdit }: { d: ReservationDetail; canEdit: boolean }) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [text, setText] = useState(d.note ?? '');
  const [inPlan, setInPlan] = useState(d.noteInPlan === true);
  const [conflict, setConflict] = useState(false);

  const save = useMutation({
    mutationFn: () => updateReservation(d.id as number, { version: d.version as number, note: text, noteInPlan: inPlan }),
    onSuccess: async (saved) => {
      setConflict(false);
      setEditing(false);
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
    <Paper p="sm" radius="md" bg="yellow.0" withBorder data-testid="note-panel">
      <Stack gap="xs">
        <Group justify="space-between">
          <Text fw={600}>{t('reservations.note.title')}</Text>
          {canEdit && !editing && (
            <WriteAction>
              <Button size="compact-sm" variant="light" onClick={() => setEditing(true)}>{t('reservations.note.edit')}</Button>
            </WriteAction>
          )}
        </Group>
        {!editing && (
          <>
            <Text size="sm" data-testid="note-text" style={{ whiteSpace: 'pre-wrap' }} c={d.note ? undefined : 'dimmed'}>
              {d.note || t('reservations.note.empty')}
            </Text>
            {d.note && d.noteInPlan && <Text size="xs" c="dimmed" data-testid="note-in-plan">✎ {t('reservations.note.inPlan')}</Text>}
          </>
        )}
        {editing && (
          <>
            <Textarea label={t('reservations.note.label')} value={text} maxLength={2000} minRows={2} onChange={(e) => setText(e.currentTarget.value)} />
            <Checkbox label={t('reservations.note.showInPlan')} checked={inPlan} onChange={(e) => setInPlan(e.currentTarget.checked)} />
            <Group>
              <WriteAction>
                <Button onClick={() => save.mutate()} loading={save.isPending}>{t('reservations.note.save')}</Button>
              </WriteAction>
              <Button variant="subtle" onClick={() => { setEditing(false); setConflict(false); setText(d.note ?? ''); setInPlan(d.noteInPlan === true); }}>
                {t('reservations.note.cancel')}
              </Button>
            </Group>
          </>
        )}
        {conflict && <Alert color="orange" data-testid="note-conflict">{t('reservations.conflict')}</Alert>}
        {save.isError && !conflict && <Alert color="red">{t('errors.generic')}</Alert>}
      </Stack>
    </Paper>
  );
}

/** Komentáre karty (najnovšie hore; z detailu, teda aj offline z trezora). Pridanie a skrytie len online s `RESERVATION_EDIT`. */
function CommentsPanel({ d, canEdit }: { d: ReservationDetail; canEdit: boolean }) {
  const { t, i18n } = useTranslation();
  const client = useQueryClient();
  const [text, setText] = useState('');
  const [hiding, setHiding] = useState<ReservationComment | null>(null);
  const refresh = async () => {
    await client.invalidateQueries({ queryKey: ['reservations'] });
    await client.invalidateQueries({ queryKey: ['plan'] });
  };
  const add = useMutation({
    mutationFn: () => addComment(d.id as number, text.trim()),
    onSuccess: async () => {
      setText('');
      await refresh();
    },
  });
  const hide = useMutation({
    mutationFn: (c: ReservationComment) => hideComment(d.id as number, c.id as number),
    onSuccess: async () => {
      setHiding(null);
      await refresh();
    },
  });
  const list = [...(d.comments ?? [])].sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? '') || (b.id ?? 0) - (a.id ?? 0));

  return (
    <Stack gap="xs" data-testid="comments-panel">
      <Text fw={600}>{t('reservations.comments.title')}</Text>
      {list.length === 0 && <Text size="sm" c="dimmed">{t('reservations.comments.empty')}</Text>}
      {list.map((c) => (
        <Group key={c.id} justify="space-between" align="flex-start" wrap="nowrap" data-testid="comment">
          <Stack gap={0}>
            <Text size="xs" c="dimmed">
              {t('reservations.comments.author', { id: c.authorId })} · {fmtTime(c.createdAt, i18n.language)}
            </Text>
            <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>{c.text}</Text>
          </Stack>
          {canEdit && (
            <WriteAction>
              <Button size="compact-sm" variant="subtle" color="red" onClick={() => setHiding(c)}>{t('reservations.comments.hide')}</Button>
            </WriteAction>
          )}
        </Group>
      ))}
      {canEdit && (
        <Stack gap="xs">
          <Textarea label={t('reservations.comments.newLabel')} value={text} maxLength={2000} minRows={2} onChange={(e) => setText(e.currentTarget.value)} />
          <Group>
            <WriteAction>
              <Button onClick={() => add.mutate()} loading={add.isPending} disabled={text.trim() === ''}>{t('reservations.comments.add')}</Button>
            </WriteAction>
          </Group>
          {add.isError && <Alert color="red">{t('errors.generic')}</Alert>}
        </Stack>
      )}
      <Modal opened={hiding !== null} onClose={() => setHiding(null)} title={t('reservations.comments.hideTitle')}>
        <Stack>
          <Text size="sm">{t('reservations.comments.hideText')}</Text>
          {hide.isError && <Alert color="red">{t('errors.generic')}</Alert>}
          <Group justify="flex-end">
            <Button variant="subtle" onClick={() => setHiding(null)}>{t('reservations.comments.hideCancel')}</Button>
            <Button color="red" loading={hide.isPending} onClick={() => hiding && hide.mutate(hiding)}>{t('reservations.comments.hideConfirm')}</Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}
