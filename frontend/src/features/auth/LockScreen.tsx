import { Alert, Box, Button, Paper, PasswordInput, Stack, Text, Title } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { queryKeys } from '../../shared/api/queryKeys';
import { connection } from '../../shared/offline';
import { loginFlow } from './loginFlow';
import { useLoginErrorText } from './LoginPage';

/**
 * Obrazovka zamknutia po nečinnosti (DLD kap. 6). Odomknutie: online → `POST /api/auth/login`,
 * offline → heslo trezora (rieši `loginFlow`).
 */
export function LockScreen({ username }: { username: string }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const errorText = useLoginErrorText();
  const [password, setPassword] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  // Zamknutá aplikácia nesmie držať údaje v pamäti dotazov.
  useEffect(() => {
    qc.removeQueries({ predicate: (q) => q.queryKey[0] !== queryKeys.me[0] });
  }, [qc]);

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const res = await loginFlow(username, password);
      setPassword('');
      connection.unlock(res.offline);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box mih="100vh" bg="#EEF5FC" display="flex" style={{ alignItems: 'center', justifyContent: 'center' }}>
      <Paper component="form" onSubmit={submit} withBorder radius="lg" p="xl" w={400} bg="white">
        <Stack gap="md">
          <div>
            <Title order={2}>{t('auth.lockedTitle')}</Title>
            <Text c="dimmed">{t('auth.lockedHint')}</Text>
          </div>
          <Text fw={500}>{username}</Text>
          {error !== null && <Alert color="orange" role="alert">{errorText(error)}</Alert>}
          <PasswordInput label={t('auth.password')} autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.currentTarget.value)} required data-autofocus />
          <Button type="submit" loading={busy}>{t('auth.unlock')}</Button>
        </Stack>
      </Paper>
    </Box>
  );
}
