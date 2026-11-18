import { Paper, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';

export function PlanPlaceholder() {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="xl" radius="lg" bg="white">
      <Title order={2}>{t('plan.comingSoon')}</Title>
    </Paper>
  );
}
