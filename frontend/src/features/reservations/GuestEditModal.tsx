import { Alert, Button, Group, Modal, Stack, Text, TextInput } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../shared/api/errors';
import { errorText } from '../../shared/api/errorText';
import { WriteAction } from '../../shared/offline';
import { updateClient, type ReservationGuest } from './reservationsApi';

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const NATIONALITY = /^[A-Za-z]{2}$/;

interface Fields {
  firstName: string;
  lastName: string;
  birthDate: string;
  nationality: string;
  email: string;
  phone: string;
}

type Errors = Partial<Record<keyof Fields, string>>;

const fieldsOf = (g: ReservationGuest): Fields => ({
  firstName: g.firstName ?? '',
  lastName: g.lastName ?? '',
  birthDate: g.birthDate ?? '',
  nationality: g.nationality ?? '',
  email: g.email ?? '',
  phone: g.phone ?? '',
});

/**
 * Dialóg opravy údajov hosťa (napr. preklep v kontakte). Údaje patria klientovi, ktorý môže byť na
 * viacerých kartách, preto sa zmena prejaví všade. Formulár sa po obnovení údajov (nová
 * `clientVersion`) načíta znova, upozornenie na konflikt zostane.
 */
export function GuestEditModal({ guest, onClose }: { guest: ReservationGuest; onClose: () => void }) {
  const { t } = useTranslation();
  const [conflict, setConflict] = useState(false);
  return (
    <Modal opened onClose={onClose} title={t('reservations.guestEdit.title')} size="lg">
      <GuestEditForm key={guest.clientVersion} guest={guest} conflict={conflict} onConflict={setConflict} onClose={onClose} />
    </Modal>
  );
}

function GuestEditForm({
  guest,
  conflict,
  onConflict,
  onClose,
}: {
  guest: ReservationGuest;
  conflict: boolean;
  onConflict: (c: boolean) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const client = useQueryClient();
  const [v, setV] = useState<Fields>(() => fieldsOf(guest));
  const [errors, setErrors] = useState<Errors>({});

  const save = useMutation({
    mutationFn: () =>
      updateClient(guest.clientId as number, {
        version: guest.clientVersion as number,
        firstName: v.firstName.trim(),
        lastName: v.lastName.trim(),
        birthDate: v.birthDate || undefined,
        nationality: v.nationality.trim() ? v.nationality.trim().toUpperCase() : undefined,
        email: v.email.trim() || undefined,
        phone: v.phone.trim() || undefined,
      }),
    onSuccess: async () => {
      onConflict(false);
      await client.invalidateQueries({ queryKey: ['reservations'] });
      await client.invalidateQueries({ queryKey: ['plan'] });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        onConflict(true);
        void client.invalidateQueries({ queryKey: ['reservations', 'detail'] });
      }
    },
  });

  const validate = (): Errors => {
    const e: Errors = {};
    if (!v.firstName.trim()) e.firstName = t('reservations.guestEdit.required');
    if (!v.lastName.trim()) e.lastName = t('reservations.guestEdit.required');
    if (v.email.trim() && !EMAIL.test(v.email.trim())) e.email = t('reservations.guestEdit.invalidEmail');
    if (v.nationality.trim() && !NATIONALITY.test(v.nationality.trim())) e.nationality = t('reservations.guestEdit.invalidNationality');
    return e;
  };

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    const e = validate();
    setErrors(e);
    if (Object.keys(e).length === 0) save.mutate();
  };

  const input = (name: keyof Fields, props: { type?: string; maxLength: number; required?: boolean }) => (
    <TextInput
      label={t(`reservations.guestEdit.${name}`)}
      value={v[name]}
      error={errors[name]}
      onChange={(e) => {
        const value = e.currentTarget.value;
        setV((old) => ({ ...old, [name]: value }));
      }}
      {...props}
    />
  );

  return (
    <form onSubmit={submit} noValidate data-testid="guest-edit-form">
      <Stack gap="sm">
        <Text size="sm" c="dimmed">{t('reservations.guestEdit.sharedInfo')}</Text>
        <Group grow align="flex-start">
          {input('firstName', { maxLength: 100, required: true })}
          {input('lastName', { maxLength: 100, required: true })}
        </Group>
        <Group grow align="flex-start">
          {input('birthDate', { type: 'date', maxLength: 10 })}
          {input('nationality', { maxLength: 2 })}
        </Group>
        <Group grow align="flex-start">
          {input('email', { type: 'email', maxLength: 200 })}
          {input('phone', { type: 'tel', maxLength: 50 })}
        </Group>
        {conflict && <Alert color="orange" data-testid="guest-edit-conflict">{t('reservations.guestEdit.conflict')}</Alert>}
        {save.isError && !conflict && <Alert color="red">{errorText(t, save.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="subtle" onClick={onClose}>{t('reservations.guestEdit.cancel')}</Button>
          <WriteAction>
            <Button type="submit" loading={save.isPending}>{t('reservations.guestEdit.save')}</Button>
          </WriteAction>
        </Group>
      </Stack>
    </form>
  );
}
