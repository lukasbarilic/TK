import { Paper, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';

export function ComingSoon({ titleKey }: { titleKey: string }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="xl" radius="lg" bg="white">
      <Title order={2}>{t('shell.comingSoon', { title: t(titleKey) })}</Title>
    </Paper>
  );
}
