import { Alert, Button, Code, Group, Modal, Stack, Text } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/** Jednorazové heslo: zobrazí sa raz (stav drží len volajúci a po zavretí sa zahodí). */
export function PasswordModal({ name, password, onClose }: { name: string; password: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(password);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <Modal opened onClose={onClose} title={t('admin.passwordTitle')} closeOnClickOutside={false}>
      <Stack>
        <Text size="sm">{t('admin.passwordFor', { name })}</Text>
        <Code fz="lg" p="sm" data-testid="one-time-password">{password}</Code>
        <Alert color="orange" data-testid="password-warning">{t('admin.passwordWarning')}</Alert>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => void copy()}>{copied ? t('admin.copied') : t('admin.copy')}</Button>
          <Button onClick={onClose}>{t('admin.close')}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
