import { AppShell as MantineAppShell, Box, Stack, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Outlet } from 'react-router-dom';

export function AppShell() {
  const { t } = useTranslation();
  return (
    <MantineAppShell navbar={{ width: 240, breakpoint: 0 }} padding="lg">
      <MantineAppShell.Navbar
        p="md"
        bg="var(--mantine-color-brand-1)"
        style={{ borderColor: 'var(--mantine-color-brand-2)' }}
      >
        <Stack gap={0} mb="lg">
          <Text fw={700} size="xl" c="brand.9" lh={1.1}>
            {t('app.name')}
          </Text>
          <Text size="xs" c="dimmed">
            {t('app.by')}
          </Text>
        </Stack>
        <nav aria-label={t('shell.menuLabel')} data-testid="main-menu" />
      </MantineAppShell.Navbar>
      <MantineAppShell.Main bg="#EEF5FC">
        <Box>
          <Outlet />
        </Box>
      </MantineAppShell.Main>
    </MantineAppShell>
  );
}
