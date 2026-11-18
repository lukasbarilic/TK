import { Alert, Loader, Modal, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { errorText } from '../../shared/api/errorText';
import { queryKeys } from '../../shared/api/queryKeys';
import { getEvent } from './auditApi';
import { JsonDiff } from './JsonDiff';

export function EventDetailModal({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: queryKeys.auditEvent(id), queryFn: () => getEvent(id) });
  const e = q.data?.event;
  return (
    <Modal opened onClose={onClose} size="xl" title={t('audit.detailTitle', { id })}>
      <Stack data-testid="audit-detail">
        {q.isPending && <Loader size="sm" />}
        {q.isError && <Alert color="red">{errorText(t, q.error)}</Alert>}
        {e && (
          <Text size="sm">
            {e.operation} · {e.username} · {e.occurredAt ? new Date(e.occurredAt).toLocaleString() : ''} · {e.outcome}
          </Text>
        )}
        {q.data?.changes?.map((c) => (
          <Stack key={c.id} gap="xs">
            <Title order={5}>
              {c.entityType} #{c.entityId} · {c.changeType}
            </Title>
            <JsonDiff before={c.beforeState} after={c.afterState} />
          </Stack>
        ))}
        {q.data && (q.data.changes ?? []).length === 0 && <Text c="dimmed">{t('audit.noChanges')}</Text>}
      </Stack>
    </Modal>
  );
}
