import { Alert, Box, Button, Paper, PasswordInput, Stack, Text, Title } from '@mantine/core';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../shared/api/errors';
import { useChangePassword } from './hooks';

/** Povinná zmena hesla pri `mustChangePassword`; nič iné sa dovtedy nezobrazí. */
export function ChangePasswordPage() {
  const { t } = useTranslation();
  const change = useChangePassword();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const mismatch = repeat !== '' && next !== repeat;

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    if (next !== repeat) return;
    change.mutate({ currentPassword: current, newPassword: next });
  };

  const serverMessage =
    change.error instanceof ApiError ? (change.error.detail ?? change.error.title) : undefined;

  return (
    <Box mih="100vh" bg="#EEF5FC" display="flex" style={{ alignItems: 'center', justifyContent: 'center' }}>
      <Paper component="form" onSubmit={submit} withBorder radius="lg" p="xl" w={400} bg="white">
        <Stack gap="md">
          <div>
            <Title order={2}>{t('auth.changeTitle')}</Title>
            <Text c="dimmed">{t('auth.changeHint')}</Text>
          </div>
          {change.isError && (
            <Alert color="orange" role="alert">{serverMessage ?? t('auth.changeFailed')}</Alert>
          )}
          <PasswordInput label={t('auth.currentPassword')} autoComplete="current-password"
            value={current} onChange={(e) => setCurrent(e.currentTarget.value)} required />
          <PasswordInput label={t('auth.newPassword')} autoComplete="new-password"
            value={next} onChange={(e) => setNext(e.currentTarget.value)} required />
          <PasswordInput label={t('auth.repeatPassword')} autoComplete="new-password"
            value={repeat} onChange={(e) => setRepeat(e.currentTarget.value)} required
            error={mismatch ? t('auth.passwordsDiffer') : undefined} />
          <Button type="submit" loading={change.isPending}>{t('auth.changeSubmit')}</Button>
        </Stack>
      </Paper>
    </Box>
  );
}
