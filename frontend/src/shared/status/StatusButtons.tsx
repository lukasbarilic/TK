import { Button, Group } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { components } from '../api/schema';
import { WriteAction } from '../offline';
import { statusActions } from './rules';
import type { StatusAction } from './statusApi';

type Me = components['schemas']['MeView'];

/** Tlačidlá akcií podľa stavu karty; všetky zapisujú, preto sú bez spojenia neaktívne. */
export function StatusButtons({ status, me, busy, onAction }: { status: string | undefined; me: Me | null | undefined; busy?: boolean; onAction: (a: StatusAction) => void }) {
  const { t } = useTranslation();
  const actions = statusActions(status, me);
  if (actions.length === 0) return null;
  return (
    <Group gap="xs" data-testid="status-buttons">
      {actions.map((a, i) => (
        <WriteAction key={a}>
          <Button size="compact-md" variant={i === 0 ? 'filled' : 'default'} color={a === 'cancel' ? 'red' : undefined} loading={busy} onClick={() => onAction(a)}>
            {t(`status.actions.${a}`)}
          </Button>
        </WriteAction>
      ))}
    </Group>
  );
}
