import { Paper, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';

export function ForbiddenPage() {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="xl" radius="lg" bg="white">
      <Title order={2}>{t('auth.forbiddenTitle')}</Title>
      <Text c="dimmed">{t('auth.forbiddenText')}</Text>
    </Paper>
  );
}
