import { Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const show = (v: unknown) => (v === undefined ? '–' : JSON.stringify(v));

/** Porovnanie stavu pred a po zmene: jeden riadok na kľúč, zmenené riadky sú označené. */
export function JsonDiff({ before, after }: { before: unknown; after: unknown }) {
  const { t } = useTranslation();
  const b = isObj(before) ? before : {};
  const a = isObj(after) ? after : {};
  const keys = [...new Set([...Object.keys(b), ...Object.keys(a)])].sort();
  if (keys.length === 0) return <Text c="dimmed">{t('audit.noState')}</Text>;
  return (
    <Table data-testid="json-diff">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>{t('audit.field')}</Table.Th>
          <Table.Th>{t('audit.before')}</Table.Th>
          <Table.Th>{t('audit.after')}</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {keys.map((k) => {
          const changed = JSON.stringify(b[k]) !== JSON.stringify(a[k]);
          return (
            <Table.Tr key={k} data-testid={changed ? 'diff-changed' : 'diff-same'} bg={changed ? 'yellow.0' : undefined}>
              <Table.Td fw={changed ? 700 : 400}>{k}</Table.Td>
              <Table.Td>{show(b[k])}</Table.Td>
              <Table.Td>{show(a[k])}</Table.Td>
            </Table.Tr>
          );
        })}
      </Table.Tbody>
    </Table>
  );
}
