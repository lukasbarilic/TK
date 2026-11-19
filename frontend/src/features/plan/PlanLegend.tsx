import { Group, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { BarColors, ColorKey } from './colors';

const ITEMS: { key: ColorKey; label: string }[] = [
  { key: 'RESERVED', label: 'plan.status.RESERVED' },
  { key: 'CHECKED_IN', label: 'plan.status.CHECKED_IN' },
  { key: 'CHECKED_OUT', label: 'plan.status.CHECKED_OUT' },
  { key: 'NO_SHOW', label: 'plan.status.NO_SHOW' },
];

/** Legenda plánu: farby pásikov podľa stavu karty a ikony. */
export function PlanLegend({ colors }: { colors: Record<ColorKey, BarColors> }) {
  const { t } = useTranslation();
  return (
    <Group gap="md" mt="sm" data-testid="plan-legend" wrap="wrap">
      {ITEMS.map((i) => (
        <Group key={i.key} gap={6}>
          <span
            data-testid="legend-swatch"
            data-color-key={i.key}
            style={{ width: 22, height: 12, borderRadius: 4, background: colors[i.key].fill, border: `1px ${i.key === 'RESERVED' ? 'dashed' : 'solid'} ${colors[i.key].border}` }}
          />
          <Text size="xs">{t(i.label)}</Text>
        </Group>
      ))}
      <Text size="xs">⇥ {t('plan.icons.arrival')}</Text>
      <Text size="xs">↦ {t('plan.icons.departure')}</Text>
      <Text size="xs">✎ {t('plan.icons.note')}</Text>
    </Group>
  );
}
