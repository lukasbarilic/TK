import { Alert, Button, Group, Modal, Progress, Stack, Text } from '@mantine/core';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { apiCall } from '../../shared/api/client';
import { useConnection } from '../../shared/offline';
import { platform } from '../../shared/platform';
import { isBelowMinimum } from './semver';

export const UPDATE_CHECK_INTERVAL_MS = 60 * 60 * 1000;

interface UpdateState {
  blocked: boolean;
  available: { version: string; notes?: string } | null;
}

/**
 * Kontrola aktualizácií (DLD kap. 12): pri štarte a každú hodinu, len keď je spojenie `online`.
 * Staršia verzia než `minClientVersion` → blokujúca obrazovka; inak nenápadné upozornenie na novú verziu.
 */
export function UpdateRuntime() {
  const { t } = useTranslation();
  const { state: conn } = useConnection();
  const [state, setState] = useState<UpdateState>({ blocked: false, available: null });
  const [installing, setInstalling] = useState(false);
  const [progress, setProgress] = useState(0);
  const [failed, setFailed] = useState(false);
  const busy = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  const check = useCallback(async () => {
    if (busy.current) return;
    busy.current = true;
    try {
      const [version, cfg, available] = await Promise.all([
        platform.app.version(),
        apiCall('get', '/api/client-config').then((r) => r.data).catch(() => undefined),
        platform.app.checkUpdate().catch(() => null),
      ]);
      if (!mounted.current) return;
      setState((prev) => ({
        blocked: cfg ? isBelowMinimum(version, cfg.minClientVersion) : prev.blocked,
        available,
      }));
    } finally {
      busy.current = false;
    }
  }, []);

  useEffect(() => {
    if (conn !== 'online') return;
    void check();
    const timer = setInterval(() => void check(), UPDATE_CHECK_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [conn, check]);

  const install = () => {
    setFailed(false);
    setInstalling(true);
    setProgress(0);
    platform.app
      .installUpdate(setProgress)
      .catch(() => setFailed(true))
      .finally(() => setInstalling(false));
  };

  // offline sa nič neukazuje ani nekontroluje
  if (conn !== 'online') return null;

  if (state.blocked) {
    return (
      <Modal
        opened
        onClose={() => {}}
        withCloseButton={false}
        closeOnClickOutside={false}
        closeOnEscape={false}
        fullScreen
        title={t('updates.requiredTitle')}
        data-testid="update-required"
      >
        <Stack maw={520}>
          <Text>{t('updates.requiredText')}</Text>
          {state.available ? (
            <Text size="sm" c="dimmed">
              {t('updates.newVersion', { version: state.available.version })}
            </Text>
          ) : (
            <Text size="sm" c="dimmed">{t('updates.notAvailable')}</Text>
          )}
          {installing && <Progress value={progress} aria-label={t('updates.installing')} />}
          {failed && <Alert color="red">{t('updates.failed')}</Alert>}
          <Group>
            <Button onClick={install} loading={installing} disabled={!state.available}>
              {t('updates.update')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    );
  }

  if (!state.available) return null;
  return (
    <Alert color="blue" variant="light" mb="sm" data-testid="update-available">
      <Group justify="space-between">
        <Text size="sm">{t('updates.available')}</Text>
        <Button size="compact-sm" onClick={install} loading={installing}>
          {t('updates.install')}
        </Button>
      </Group>
      {failed && <Text size="sm" c="red">{t('updates.failed')}</Text>}
    </Alert>
  );
}
