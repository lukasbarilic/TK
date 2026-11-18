import { Group, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useConnection, useLastSync, useSyncStatus } from '../../shared/offline';

type Variant = 'online' | 'syncing' | 'partial' | 'checking' | 'offline' | 'expired';

const COLORS: Record<Variant, string> = {
  online: 'var(--mantine-color-green-0)',
  syncing: 'var(--mantine-color-blue-0)',
  partial: 'var(--mantine-color-yellow-0)',
  checking: 'var(--mantine-color-yellow-0)',
  offline: 'var(--mantine-color-orange-1)',
  expired: 'var(--mantine-color-orange-1)',
};

/** Lišta stavu spojenia v `AppShell`: online / overujem spojenie / offline s vekom dát / synchronizujem. */
export function ConnectionBar() {
  const { t, i18n } = useTranslation();
  const { state } = useConnection();
  const sync = useSyncStatus();
  const lastSync = useLastSync();
  if (state === 'locked') return null;

  const variant: Variant =
    state === 'checking'
      ? 'checking'
      : state === 'offline'
        ? 'offline'
        : state === 'session-expired'
          ? 'expired'
          : sync.running
            ? 'syncing'
            : sync.partial
              ? 'partial'
              : 'online';
  const time = lastSync ? new Date(lastSync).toLocaleString(i18n.language) : null;
  const text =
    variant === 'offline'
      ? time
        ? t('offline.statusOffline', { time })
        : t('offline.statusOfflineNoData')
      : variant === 'online' && time
        ? t('offline.statusOnlineSynced', { time })
        : t(`offline.status.${variant}`);

  return (
    <Group role="status" aria-live="polite" data-testid="connection-bar" data-state={variant}
      px="md" py={6} mb="md" style={{ borderRadius: 8, background: COLORS[variant] }}>
      <Text size="sm" fw={500}>{text}</Text>
    </Group>
  );
}
