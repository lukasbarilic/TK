import { useDraggable, useDroppable } from '@dnd-kit/core';
import { Button, Group, Paper, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { WriteAction } from '../../shared/offline';
import type { ParkedItem } from './moves';

interface Props {
  items: ParkedItem[];
  /** Popis cieľa položky (izba + lôžko), ak už bola pustená na lôžko. */
  targetLabel: (bedId: number) => string;
  failedStayId?: number;
  saving: boolean;
  onRemove: (stayId: number) => void;
  onSave: () => void;
  onClear: () => void;
}

function ParkedRow({ item, label, failed, onRemove }: { item: ParkedItem; label?: string; failed: boolean; onRemove: () => void }) {
  const { t } = useTranslation();
  const { setNodeRef, listeners, attributes, isDragging } = useDraggable({ id: `parked:${item.stayId}`, data: { kind: 'parked', item } });
  return (
    <Paper
      ref={setNodeRef}
      {...listeners}
      {...attributes}
      withBorder
      p={6}
      radius="md"
      data-testid="parked-item"
      data-stay-id={item.stayId}
      data-failed={failed}
      style={{ opacity: isDragging ? 0.4 : 1, cursor: 'grab', touchAction: 'none', borderColor: failed ? '#CF222E' : undefined }}
    >
      <Group justify="space-between" wrap="nowrap" gap="xs">
        <div>
          <Text size="sm" fw={600}>{item.label}</Text>
          <Text size="xs" c="dimmed">{item.from} – {item.to}</Text>
          <Text size="xs" c={label ? undefined : 'orange'}>{label ? `→ ${label}` : t('plan.parking.noTarget')}</Text>
          {failed && <Text size="xs" c="red" data-testid="parked-failed">{t('plan.parking.failed')}</Text>}
        </div>
        <Button size="compact-xs" variant="subtle" onPointerDown={(e) => e.stopPropagation()} onClick={onRemove}>
          {t('plan.parking.remove')}
        </Button>
      </Group>
    </Paper>
  );
}

/** Odkladisko (DLD kap. 9.3): sem sa ťahajú pásiky, odtiaľ späť na lôžka; „Uložiť zmeny“ pošle dávku. */
export function ParkingPanel({ items, targetLabel, failedStayId, saving, onRemove, onSave, onClear }: Props) {
  const { t } = useTranslation();
  const { setNodeRef, isOver } = useDroppable({ id: 'parking', data: { kind: 'parking' } });
  const ready = items.length > 0 && items.every((i) => i.targetBedId !== undefined);
  return (
    <Paper
      ref={setNodeRef}
      withBorder
      p="sm"
      radius="lg"
      w={260}
      data-testid="parking"
      style={{ flex: 'none', background: isOver ? '#EAF3FD' : undefined, borderStyle: 'dashed' }}
    >
      <Title order={4} size="h5" mb={4}>{t('plan.parking.title')}</Title>
      <Text size="xs" c="dimmed" mb="xs">{t('plan.parking.hint')}</Text>
      <Stack gap={6} mb="sm">
        {items.map((i) => (
          <ParkedRow
            key={i.stayId}
            item={i}
            label={i.targetBedId === undefined ? undefined : targetLabel(i.targetBedId)}
            failed={failedStayId === i.stayId}
            onRemove={() => onRemove(i.stayId)}
          />
        ))}
        {items.length === 0 && <Text size="sm" c="dimmed">{t('plan.parking.empty')}</Text>}
      </Stack>
      <Group gap="xs">
        <WriteAction>
          <Button size="xs" disabled={!ready || saving} loading={saving} onClick={onSave}>{t('plan.parking.save')}</Button>
        </WriteAction>
        <Button size="xs" variant="default" disabled={items.length === 0 || saving} onClick={onClear}>{t('plan.parking.clear')}</Button>
      </Group>
    </Paper>
  );
}
