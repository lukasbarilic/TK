import { Button, Group, Modal, NativeSelect, TextInput } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

interface Props {
  /** Možné cieľové lôžka. */
  beds: { value: string; label: string }[];
  from: string;
  to: string;
  onSubmit: (bedId: number, fromDate: string) => void;
  onClose: () => void;
}

/** „Presťahovať od…“ z kontextového menu: výber lôžka a dňa, od ktorého sa pobyt presúva. */
export function MoveFromDialog({ beds, from, to, onSubmit, onClose }: Props) {
  const { t } = useTranslation();
  const [bed, setBed] = useState(beds[0]?.value ?? '');
  const [day, setDay] = useState(from);
  const valid = bed !== '' && day >= from && day < to;
  return (
    <Modal opened onClose={onClose} title={t('plan.menu.moveFrom')} centered>
      <NativeSelect label={t('plan.moveFrom.bed')} data={beds} value={bed} onChange={(e) => setBed(e.currentTarget.value)} mb="sm" />
      <TextInput label={t('plan.moveFrom.day')} type="date" min={from} max={to} value={day} onChange={(e) => setDay(e.currentTarget.value)} error={valid ? undefined : t('plan.moveFrom.invalid')} mb="md" />
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>{t('confirm.cancel')}</Button>
        <Button disabled={!valid} onClick={() => onSubmit(Number(bed), day)}>{t('plan.moveFrom.submit')}</Button>
      </Group>
    </Modal>
  );
}
