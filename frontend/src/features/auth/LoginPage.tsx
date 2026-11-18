import { Alert, Box, Button, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, isNetError } from '../../shared/api/errors';
import { useLogin } from './hooks';

/** Zobrazí text chyby prihlásenia podľa DLD kap. 11 (401, 423, sieťová chyba). */
export function useLoginErrorText() {
  const { t, i18n } = useTranslation();
  return (e: unknown): string => {
    if (isNetError(e)) return t('auth.serverUnavailable');
    if (e instanceof ApiError && (e.status === 423 || e.code === 'ACCOUNT_LOCKED')) {
      if (e.lockedUntil) {
        const time = new Date(e.lockedUntil).toLocaleString(i18n.language);
        return t('auth.lockedUntil', { time });
      }
      return `${t('auth.locked')} ${t('auth.lockedContactAdmin')}`;
    }
    if (e instanceof ApiError && e.status === 401) return t('auth.invalidCredentials');
    return t('errors.generic');
  };
}

export function LoginPage() {
  const { t } = useTranslation();
  const login = useLogin();
  const errorText = useLoginErrorText();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const submit = (ev: FormEvent) => {
    ev.preventDefault();
    login.mutate({ username: username.trim(), password });
  };

  return (
    <Box mih="100vh" bg="#EEF5FC" display="flex" style={{ alignItems: 'center', justifyContent: 'center' }}>
      <Paper component="form" onSubmit={submit} withBorder radius="lg" p="xl" w={400} bg="white">
        <Stack gap="md">
          <Stack gap={0}>
            <Text fw={700} size="xl" c="brand.9" lh={1.1}>{t('app.name')}</Text>
            <Text size="xs" c="dimmed">{t('app.by')}</Text>
          </Stack>
          <div>
            <Title order={2}>{t('auth.loginTitle')}</Title>
            <Text c="dimmed">{t('auth.loginHint')}</Text>
          </div>
          {login.isError && <Alert color="orange" role="alert">{errorText(login.error)}</Alert>}
          <TextInput
            label={t('auth.username')}
            autoComplete="username"
            value={username}
            onChange={(e) => setUsername(e.currentTarget.value)}
            required
          />
          <PasswordInput
            label={t('auth.password')}
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
            required
          />
          <Button type="submit" loading={login.isPending}>{t('auth.submit')}</Button>
        </Stack>
      </Paper>
    </Box>
  );
}
