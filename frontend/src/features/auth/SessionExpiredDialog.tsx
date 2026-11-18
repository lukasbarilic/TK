import { Alert, Button, Modal, PasswordInput, Stack, Text } from '@mantine/core';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useConnection } from '../../shared/offline';
import { useMe, useReauthenticate } from './hooks';
import { useLoginErrorText } from './LoginPage';
import { useSessionExpired } from './session';

/** Dialóg „Relácia vypršala“ nad aktuálnou obrazovkou; po prihlásení sa dáta obnovia. */
export function SessionExpiredDialog() {
  const { t } = useTranslation();
  const flagged = useSessionExpired();
  const connState = useConnection().state;
  const expired = flagged || connState === 'session-expired';
  const me = useMe().data;
  const reauth = useReauthenticate();
  const errorText = useLoginErrorText();
  const [password, setPassword] = useState('');

  if (!expired || !me?.username) return null;
  const username = me.username;

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    reauth.mutate({ username, password }, { onSuccess: () => setPassword('') });
  };

  return (
    <Modal opened onClose={() => {}} withCloseButton={false} closeOnClickOutside={false}
      closeOnEscape={false} title={t('auth.sessionExpiredTitle')} centered>
      <form onSubmit={submit}>
        <Stack gap="md">
          <Text c="dimmed">{t('auth.sessionExpiredHint')}</Text>
          <Text fw={500}>{username}</Text>
          {reauth.isError && <Alert color="orange" role="alert">{errorText(reauth.error)}</Alert>}
          <PasswordInput label={t('auth.password')} autoComplete="current-password"
            value={password} onChange={(e) => setPassword(e.currentTarget.value)} required data-autofocus />
          <Button type="submit" loading={reauth.isPending}>{t('auth.submit')}</Button>
        </Stack>
      </form>
    </Modal>
  );
}
