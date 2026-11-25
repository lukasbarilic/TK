import { Alert, Button, Checkbox, Group, Modal, Stack, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../shared/api/errors';
import { errorText } from '../../shared/api/errorText';
import { putLocal, WriteAction } from '../../shared/offline';
import { DS } from '../../shared/offline/datasets';
import { updateReservation, type ReservationDetail } from './reservationsApi';

/**
 * Dialóg úpravy termínu a príznakov karty; `version` ide v tele (optimistic locking). Po obnovení
 * karty (nová `version`) sa formulár načíta znova, upozornenie na konflikt zostane.
 */
export function ReservationEditModal({ d, onClose }: { d: ReservationDetail; onClose: () => void }) {
  const { t } = useTranslation();
  const [conflict, setConflict] = useState(false);
  return (
    <Modal opened onClose={onClose} title={t('reservations.edit')}>
      <EditForm key={d.version} d={d} conflict={conflict} onConflict={setConflict} onClose={onClose} />
    </Modal>
  );
}

function EditForm({
  d,
  conflict,
  onConflict,
  onClose,
}: {
  d: ReservationDetail;
  conflict: boolean;
  onConflict: (c: boolean) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [arrival, setArrival] = useState(d.arrivalDate ?? '');
  const [departure, setDeparture] = useState(d.departureDate ?? '');
  const [doNotMove, setDoNotMove] = useState(d.doNotMove === true);
  const [exclusiveRoomUse, setExclusiveRoomUse] = useState(d.exclusiveRoomUse === true);

  const save = useMutation({
    mutationFn: () =>
      updateReservation(d.id as number, { version: d.version as number, arrivalDate: arrival, departureDate: departure, doNotMove, exclusiveRoomUse }),
    onSuccess: async (saved) => {
      onConflict(false);
      await putLocal(DS.reservation, String(d.id), saved);
      await client.invalidateQueries({ queryKey: ['reservations'] });
      await client.invalidateQueries({ queryKey: ['plan'] });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        onConflict(true);
        void client.invalidateQueries({ queryKey: ['reservations', 'detail', d.id] });
      }
    },
  });

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    save.mutate();
  };

  return (
    <form onSubmit={submit} data-testid="reservation-edit">
      <Stack gap="sm">
        <Group grow align="flex-start">
          <TextInput type="date" label={t('reservations.arrival')} value={arrival} onChange={(e) => setArrival(e.currentTarget.value)} />
          <TextInput type="date" label={t('reservations.departure')} value={departure} onChange={(e) => setDeparture(e.currentTarget.value)} />
        </Group>
        <Checkbox label={t('reservations.doNotMove')} checked={doNotMove} onChange={(e) => setDoNotMove(e.currentTarget.checked)} />
        <Checkbox
          label={t('reservations.exclusiveRoomUse')}
          checked={exclusiveRoomUse}
          onChange={(e) => setExclusiveRoomUse(e.currentTarget.checked)}
        />
        {conflict && <Alert color="orange" data-testid="version-conflict">{t('reservations.conflict')}</Alert>}
        {save.isError && !conflict && <Alert color="red">{errorText(t, save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="subtle" onClick={onClose}>{t('reservations.editCancel')}</Button>
          <WriteAction>
            <Button type="submit" loading={save.isPending}>{t('reservations.save')}</Button>
          </WriteAction>
        </Group>
      </Stack>
    </form>
  );
}
