import { Alert, Button, Group, Loader, NativeSelect, Paper, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorText } from '../../shared/api/errorText';
import { queryKeys } from '../../shared/api/queryKeys';
import { useConnection, WriteAction } from '../../shared/offline';
import { listUsers, resetPassword, unlockUser, type AdminUser } from './adminApi';
import { PasswordModal } from './PasswordModal';
import { RolesModal } from './RolesModal';
import { UserFormModal } from './UserFormModal';

type Status = NonNullable<AdminUser['status']>;
type Dialog = { kind: 'form'; user: AdminUser | null } | { kind: 'roles'; user: AdminUser } | { kind: 'password'; name: string; password: string };

const nameOf = (u: AdminUser) => u.displayName ?? u.username ?? '';

export const matchesFilter = (u: AdminUser, text: string, status: Status | ''): boolean => {
  const q = text.trim().toLowerCase();
  const hay = `${u.displayName ?? ''} ${u.username ?? ''}`.toLowerCase();
  return (q === '' || hay.includes(q)) && (status === '' || u.status === status);
};

/** Administrácia používateľov (HLD-ubytovanie kap. 8, F13). Len online; prístup stráži `USER_ADMIN` v routeri. */
export function UsersPage() {
  const { t, i18n } = useTranslation();
  const qc = useQueryClient();
  const { canWrite } = useConnection();
  const [text, setText] = useState('');
  const [status, setStatus] = useState<Status | ''>('');
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const users = useQuery({ queryKey: queryKeys.adminUsers, queryFn: listUsers, enabled: canWrite });
  const refresh = () => void qc.invalidateQueries({ queryKey: queryKeys.adminUsers });
  const rows = (users.data ?? []).filter((u) => matchesFilter(u, text, status));

  const act = async (fn: () => Promise<void>) => {
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setError(errorText(t, e));
    }
  };
  const reset = (u: AdminUser) =>
    act(async () => {
      const r = await resetPassword(u.id as number);
      refresh();
      if (r.oneTimePassword) setDialog({ kind: 'password', name: nameOf(u), password: r.oneTimePassword });
    });
  const unlock = (u: AdminUser) =>
    act(async () => {
      await unlockUser(u.id as number);
      refresh();
      setNotice(t('admin.unlocked'));
    });

  const roleText = (u: AdminUser) =>
    (u.roles ?? []).map((r) => r.roleName ?? r.roleCode).join(', ') || t('admin.noRoles');
  const lastLogin = (u: AdminUser) =>
    u.lastLoginAt ? new Date(u.lastLoginAt).toLocaleString(i18n.language, { day: 'numeric', month: 'numeric', hour: '2-digit', minute: '2-digit' }) : '–';

  return (
    <Stack data-testid="users-page">
      <Group justify="space-between">
        <Title order={2}>{t('admin.title')}</Title>
        <WriteAction>
          <Button onClick={() => setDialog({ kind: 'form', user: null })}>+ {t('admin.newUser')}</Button>
        </WriteAction>
      </Group>

      {!canWrite && <Alert color="orange" data-testid="admin-offline">{t('admin.offline')}</Alert>}
      {error && <Alert color="red" data-testid="admin-error">{error}</Alert>}
      {notice && <Alert color="teal" data-testid="admin-notice">{notice}</Alert>}

      <Group>
        <TextInput label={t('admin.searchLabel')} value={text} onChange={(e) => setText(e.currentTarget.value)} />
        <NativeSelect
          label={t('admin.statusLabel')}
          value={status}
          onChange={(e) => setStatus(e.currentTarget.value as Status | '')}
          data={[{ value: '', label: t('admin.statusAll') }, ...(['ACTIVE', 'LOCKED', 'DISABLED'] as const).map((s) => ({ value: s, label: t(`admin.status.${s}`) }))]}
        />
      </Group>

      {canWrite && users.isPending && <Loader size="sm" />}
      {canWrite && users.isError && <Alert color="red">{errorText(t, users.error)}</Alert>}

      {canWrite && users.data && (
        <Paper withBorder radius="lg" bg="white" style={{ overflow: 'hidden' }}>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.colUser')}</Table.Th>
                <Table.Th>{t('admin.colRoles')}</Table.Th>
                <Table.Th>{t('admin.colStatus')}</Table.Th>
                <Table.Th>{t('admin.colLastLogin')}</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((u) => (
                <Table.Tr key={u.id} data-testid="user-row">
                  <Table.Td>
                    <Text fw={600}>{u.displayName}</Text>
                    <Text size="xs" c="dimmed">{u.username}</Text>
                  </Table.Td>
                  <Table.Td>{roleText(u)}</Table.Td>
                  <Table.Td>{u.status ? t(`admin.status.${u.status}`) : ''}</Table.Td>
                  <Table.Td>{lastLogin(u)}</Table.Td>
                  <Table.Td>
                    <Group gap="xs" justify="flex-end" wrap="nowrap">
                      <Button size="xs" variant="default" aria-label={`${t('admin.edit')} ${nameOf(u)}`} onClick={() => setDialog({ kind: 'form', user: u })}>{t('admin.edit')}</Button>
                      <Button size="xs" variant="default" aria-label={`${t('admin.roles')} ${nameOf(u)}`} onClick={() => setDialog({ kind: 'roles', user: u })}>{t('admin.roles')}</Button>
                      <Button size="xs" variant="default" aria-label={`${t('admin.resetPassword')} ${nameOf(u)}`} onClick={() => void reset(u)}>{t('admin.resetPassword')}</Button>
                      {u.status === 'LOCKED' && (
                        <Button size="xs" variant="default" aria-label={`${t('admin.unlock')} ${nameOf(u)}`} onClick={() => void unlock(u)}>{t('admin.unlock')}</Button>
                      )}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
              {rows.length === 0 && (
                <Table.Tr><Table.Td colSpan={5}><Text c="dimmed">{t('admin.empty')}</Text></Table.Td></Table.Tr>
              )}
            </Table.Tbody>
          </Table>
        </Paper>
      )}

      {dialog?.kind === 'form' && (
        <UserFormModal
          user={dialog.user}
          onClose={() => setDialog(null)}
          onDone={(r) => {
            refresh();
            setDialog(r.oneTimePassword ? { kind: 'password', name: nameOf(r.user), password: r.oneTimePassword } : null);
          }}
        />
      )}
      {dialog?.kind === 'roles' && (
        <RolesModal user={dialog.user} onClose={() => setDialog(null)} onDone={() => { refresh(); setDialog(null); }} />
      )}
      {dialog?.kind === 'password' && (
        <PasswordModal name={dialog.name} password={dialog.password} onClose={() => setDialog(null)} />
      )}
    </Stack>
  );
}
