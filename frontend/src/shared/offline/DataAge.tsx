import { Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

/** Vek údajov z trezora (DLD kap. 5.4): ukáže sa len pre `source: 'cache'`. */
export function DataAge({ source, syncedAt }: { source: 'server' | 'cache'; syncedAt?: string }) {
  const { t, i18n } = useTranslation();
  if (source !== 'cache' || !syncedAt) return null;
  return (
    <Text size="xs" c="dimmed" data-testid="data-age">
      {t('offline.dataAge', { time: new Date(syncedAt).toLocaleString(i18n.language) })}
    </Text>
  );
}
