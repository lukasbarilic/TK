import { Button, Group, List, Modal, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

interface Props {
  /** Kódy upozornení z odpovede 409 `confirmable` (napr. `RELEASES_HELD_BED`). */
  warnings: string[];
  loading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

/** Upozornenia na potvrdenie (DLD kap. 8): slovenský text ku každému kódu; „Pokračovať“ pošle požiadavku znova s `confirm`. */
export function ConfirmWarningsDialog({ warnings, loading, onConfirm, onCancel }: Props) {
  const { t } = useTranslation();
  return (
    <Modal opened={warnings.length > 0} onClose={onCancel} title={t('confirm.title')} centered>
      <Text size="sm" mb="xs">{t('confirm.intro')}</Text>
      <List size="sm" mb="md" data-testid="confirm-warnings">
        {warnings.map((w) => (
          <List.Item key={w}>{t(`errors.${w}`, { defaultValue: w })}</List.Item>
        ))}
      </List>
      <Group justify="flex-end">
        <Button variant="default" onClick={onCancel}>{t('confirm.cancel')}</Button>
        <Button onClick={onConfirm} loading={loading}>{t('confirm.proceed')}</Button>
      </Group>
    </Modal>
  );
}
