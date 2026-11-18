import { Alert, Button, Group, Modal, NativeSelect, Stack, Text, TextInput } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../../shared/api/errors';
import { errorText } from '../../shared/api/errorText';
import { assignRoles, listCentres, listReceptions, listRoles, type AdminUser, type RoleAssignment } from './adminApi';

const toInput = (u: AdminUser): RoleAssignment[] =>
  (u.roles ?? []).map(({ roleCode, centreId, receptionId, validFrom, validTo }) => ({ roleCode, centreId, receptionId, validFrom, validTo }));

const numOrUndef = (v: string) => (v === '' ? undefined : Number(v));

/** Priradenie rolí používateľovi: rola (číselník z B07), stredisko, recepcia, platnosť od–do. */
export function RolesModal({ user, onClose, onDone }: { user: AdminUser; onClose: () => void; onDone: (u: AdminUser) => void }) {
  const { t } = useTranslation();
  const [rows, setRows] = useState<RoleAssignment[]>(() => toInput(user));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const roles = useQuery({ queryKey: ['admin', 'roles'], queryFn: listRoles });
  const centres = useQuery({ queryKey: ['admin', 'centres'], queryFn: listCentres });
  const receptions = useQuery({ queryKey: ['admin', 'receptions'], queryFn: listReceptions });

  const roleOptions = [...new Map((roles.data ?? []).filter((r) => r.code).map((r) => [r.code as string, r.name ?? (r.code as string)])).entries()];
  const set = (i: number, patch: Partial<RoleAssignment>) => setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const violations = error instanceof ApiError ? error.fieldViolations : [];

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      onDone(await assignRoles(user.id as number, rows));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  const scopeOptions = (list: { id?: number; name?: string; code?: string }[] | undefined) => [
    { value: '', label: t('admin.any') },
    ...(list ?? []).filter((x) => x.id !== undefined).map((x) => ({ value: String(x.id), label: x.name ?? x.code ?? String(x.id) })),
  ];

  return (
    <Modal opened onClose={onClose} size="xl" title={t('admin.rolesTitle', { name: user.displayName ?? user.username })}>
      <Stack>
        {error !== null && (
          <Alert color="red" data-testid="roles-error">
            {violations.length === 0 ? errorText(t, error) : violations.map((v) => <Text key={`${v.field}-${v.message}`} size="sm">{v.message}</Text>)}
          </Alert>
        )}
        {rows.map((r, i) => (
          <Group key={i} align="flex-end" wrap="nowrap" data-testid="role-row">
            <NativeSelect
              label={t('admin.role')}
              value={r.roleCode ?? ''}
              onChange={(e) => set(i, { roleCode: e.currentTarget.value })}
              data={[{ value: '', label: '—' }, ...roleOptions.map(([value, label]) => ({ value, label }))]}
            />
            <NativeSelect label={t('admin.centre')} value={r.centreId === undefined ? '' : String(r.centreId)} onChange={(e) => set(i, { centreId: numOrUndef(e.currentTarget.value) })} data={scopeOptions(centres.data)} />
            <NativeSelect label={t('admin.reception')} value={r.receptionId === undefined ? '' : String(r.receptionId)} onChange={(e) => set(i, { receptionId: numOrUndef(e.currentTarget.value) })} data={scopeOptions(receptions.data)} />
            <TextInput type="date" label={t('admin.validFrom')} value={r.validFrom ?? ''} onChange={(e) => set(i, { validFrom: e.currentTarget.value || undefined })} />
            <TextInput type="date" label={t('admin.validTo')} value={r.validTo ?? ''} onChange={(e) => set(i, { validTo: e.currentTarget.value || undefined })} />
            <Button variant="subtle" color="red" onClick={() => setRows((rs) => rs.filter((_, j) => j !== i))}>{t('admin.removeRole')}</Button>
          </Group>
        ))}
        <Group justify="space-between">
          <Button variant="default" onClick={() => setRows((rs) => [...rs, {}])}>{t('admin.addRole')}</Button>
          <Group>
            <Button variant="default" onClick={onClose}>{t('shell.cancel')}</Button>
            <Button loading={busy} onClick={() => void save()}>{t('admin.save')}</Button>
          </Group>
        </Group>
      </Stack>
    </Modal>
  );
}
