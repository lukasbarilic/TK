import { AppShell as MantineAppShell, Box, Button, Group, Menu, Modal, NavLink, Select, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink as RouterLink, Outlet } from 'react-router-dom';
import { apiCall } from '../../shared/api/client';
import { queryKeys } from '../../shared/api/queryKeys';
import { ConnectionBar, ConnectionRuntime } from '../../features/offline';
import { receptionInScope, useLogout, useLogoutAndWipe, useMe } from '../../features/auth';
import { UpdateRuntime } from '../../features/updates';
import { visibleMenu } from '../menu';
import { HistoryShortcuts } from '../../shared/navigation/HistoryShortcuts';

// Oprávnenia, ktoré sa viažu na recepciu; z nich sa určuje „recepcie v rozsahu“.
const RECEPTION_PERMISSIONS = ['PLAN_VIEW', 'RESERVATION_VIEW', 'BUSINESS_DAY_CLOSE'];

export function AppShell() {
  const { t, i18n } = useTranslation();
  const me = useMe().data;
  const logout = useLogout();
  const wipe = useLogoutAndWipe();
  const [confirmWipe, setConfirmWipe] = useState(false);
  const [chosen, setChosen] = useState<number | null>(null);

  const receptions = useQuery({
    queryKey: queryKeys.receptions,
    queryFn: async () => (await apiCall('get', '/api/catalog/receptions')).data,
    enabled: RECEPTION_PERMISSIONS.some((c) => me?.permissions?.some((p) => p.code === c)),
  });
  const inScope = (receptions.data ?? []).filter(
    (r) => r.id !== undefined && r.active !== false && receptionInScope(me, RECEPTION_PERMISSIONS, r.id),
  );
  const ids = inScope.map((r) => r.id as number);
  const receptionId =
    chosen !== null && ids.includes(chosen)
      ? chosen
      : me?.defaultReceptionId !== undefined && ids.includes(me.defaultReceptionId)
        ? me.defaultReceptionId
        : ids[0];

  const businessDay = useQuery({
    queryKey: queryKeys.businessDay(receptionId ?? 0),
    queryFn: async () =>
      (await apiCall('get', '/api/receptions/{id}/business-day', { path: { id: receptionId as number } })).data,
    enabled: receptionId !== undefined,
  });
  const date = businessDay.data?.businessDate;
  const dateText = date ? new Date(`${date}T00:00:00Z`).toLocaleDateString(i18n.language, { timeZone: 'UTC' }) : null;

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
        {ids.length > 0 && (
          <Stack gap="xs" mb="md">
            <Select
              label={t('shell.receptionLabel')}
              allowDeselect={false}
              data={inScope.map((r) => ({ value: String(r.id), label: r.name ?? r.code ?? String(r.id) }))}
              value={receptionId === undefined ? null : String(receptionId)}
              onChange={(v) => setChosen(v === null ? null : Number(v))}
            />
            <Text size="sm" data-testid="business-day">
              {t('shell.businessDay')}: {dateText ?? '–'}
            </Text>
          </Stack>
        )}
        <nav aria-label={t('shell.menuLabel')} data-testid="main-menu">
          {visibleMenu(me).map((m) => (
            <NavLink key={m.path} component={RouterLink} to={m.path} label={t(m.labelKey)} />
          ))}
        </nav>
        <Box mt="auto">
          <Menu position="top-start" withinPortal>
            <Menu.Target>
              <Button variant="subtle" size="compact-sm" px={0} fw={500} aria-label={t('shell.profile')}>
                {me?.displayName ?? me?.username}
              </Button>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item color="red" onClick={() => setConfirmWipe(true)}>
                {t('shell.logoutAndWipe')}
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
          <br />
          <Button variant="subtle" size="compact-sm" px={0} onClick={() => logout.mutate()}>
            {t('shell.logout')}
          </Button>
        </Box>
      </MantineAppShell.Navbar>
      <Modal opened={confirmWipe} onClose={() => setConfirmWipe(false)} title={t('shell.wipeTitle')}>
        <Stack>
          <Text size="sm">{t('shell.wipeText')}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmWipe(false)}>{t('shell.cancel')}</Button>
            <Button
              color="red"
              loading={wipe.isPending}
              onClick={() => me?.username && wipe.mutate(me.username)}
            >
              {t('shell.wipeConfirm')}
            </Button>
          </Group>
        </Stack>
      </Modal>
      <MantineAppShell.Main bg="#EEF5FC">
        <Box>
          <HistoryShortcuts />
          <ConnectionRuntime />
          <ConnectionBar />
          <UpdateRuntime />
          <Outlet />
        </Box>
      </MantineAppShell.Main>
    </MantineAppShell>
  );
}
