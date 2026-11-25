import { Button, Group } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { components } from '../api/schema';
import { WriteAction } from '../offline';
import { statusActions } from './rules';
import type { StatusAction } from './statusApi';

type Me = components['schemas']['MeView'];

/** Tlačidlá akcií podľa stavu karty; všetky zapisujú, preto sú bez spojenia neaktívne. */
export function StatusButtons({ status, me, busy, onAction, primaryLast }: { status: string | undefined; me: Me | null | undefined; busy?: boolean; onAction: (a: StatusAction) => void; /** Hlavná akcia až na konci (vpravo); poradie v DOM sa zhoduje s vizuálnym. */ primaryLast?: boolean }) {
  const { t } = useTranslation();
  const actions = statusActions(status, me);
  if (actions.length === 0) return null;
  return (
    <Group gap="xs" data-testid="status-buttons">
      {(primaryLast ? actions.map((a, i) => ({ a, i })).reverse() : actions.map((a, i) => ({ a, i }))).map(({ a, i }) => (
        <WriteAction key={a}>
          <Button size="compact-md" variant={i === 0 ? 'filled' : a === 'cancel' ? 'outline' : 'default'} color={a === 'cancel' ? 'red' : undefined} loading={busy} onClick={() => onAction(a)}>
            {t(`status.actions.${a}`)}
          </Button>
        </WriteAction>
      ))}
    </Group>
  );
}
