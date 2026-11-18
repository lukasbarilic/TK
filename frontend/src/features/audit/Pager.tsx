import { Button, Group, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { PAGE_SIZE } from './auditApi';

export function Pager({ page, total, onPage }: { page: number; total: number; onPage: (p: number) => void }) {
  const { t } = useTranslation();
  const pages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  return (
    <Group justify="space-between">
      <Text size="sm" c="dimmed">{t('audit.total', { count: total })}</Text>
      <Group gap="xs">
        <Button size="xs" variant="default" disabled={page <= 0} onClick={() => onPage(page - 1)}>{t('audit.prev')}</Button>
        <Text size="sm" data-testid="audit-page">{t('audit.pageOf', { page: page + 1, pages })}</Text>
        <Button size="xs" variant="default" disabled={page + 1 >= pages} onClick={() => onPage(page + 1)}>{t('audit.next')}</Button>
      </Group>
    </Group>
  );
}
