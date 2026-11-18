import { Alert, Button, Group, Modal, NativeSelect, Stack, TextInput } from '@mantine/core';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { errorText } from '../../shared/api/errorText';
import { createUser, updateUser, type AdminUser } from './adminApi';
import { violationMap } from './violations';

type Result = { user: AdminUser; oneTimePassword?: string };

/** Založenie (user === null) alebo úprava používateľa vrátane deaktivácie; chyby polí z `fieldViolations`. */
export function UserFormModal({ user, onClose, onDone }: { user: AdminUser | null; onClose: () => void; onDone: (r: Result) => void }) {
  const { t } = useTranslation();
  const editing = user !== null && user.id !== undefined;
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState(user?.displayName ?? '');
  const [email, setEmail] = useState(user?.email ?? '');
  const [status, setStatus] = useState<'ACTIVE' | 'LOCKED' | 'DISABLED'>(user?.status ?? 'ACTIVE');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const fields = violationMap(error);
  const generic = error !== null && Object.keys(fields).length === 0;

  const submit = async (ev: FormEvent) => {
    ev.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (editing) {
        const body = { displayName, email: email || undefined, status };
        onDone({ user: await updateUser(user.id as number, body) });
      } else {
        const created = await createUser({ username, displayName, email: email || undefined });
        onDone({ user: created.user ?? {}, oneTimePassword: created.oneTimePassword });
      }
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal opened onClose={onClose} title={t(editing ? 'admin.editTitle' : 'admin.createTitle')}>
      <form onSubmit={(e) => void submit(e)} noValidate>
        <Stack>
          {error !== null && (
            <Alert color="red" data-testid="user-form-error">
              {generic ? errorText(t, error) : t('admin.violations')}
            </Alert>
          )}
          {!editing && (
            <TextInput label={t('admin.username')} value={username} onChange={(e) => setUsername(e.currentTarget.value)} error={fields.username} />
          )}
          <TextInput label={t('admin.displayName')} value={displayName} onChange={(e) => setDisplayName(e.currentTarget.value)} error={fields.displayName} />
          <TextInput label={t('admin.email')} value={email} onChange={(e) => setEmail(e.currentTarget.value)} error={fields.email} />
          {editing && (
            <NativeSelect
              label={t('admin.statusLabel')}
              value={status}
              onChange={(e) => setStatus(e.currentTarget.value as typeof status)}
              data={(['ACTIVE', 'LOCKED', 'DISABLED'] as const).map((s) => ({ value: s, label: t(`admin.status.${s}`) }))}
              error={fields.status}
            />
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>{t('shell.cancel')}</Button>
            <Button type="submit" loading={busy}>{t(editing ? 'admin.save' : 'admin.create')}</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
