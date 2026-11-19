import { Alert, Button, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { errorText } from '../../shared/api/errorText';
import { getArchivedReport, listReports } from './businessDayApi';
import { PrintableReport, usePrint } from './printReport';
import { fmtDay } from './reportFormat';

/** Archív reportov uzavretých dní: zoznam a uložený report len na čítanie (a tlač). */
export function ReportArchive({ receptionId, receptionName, onBack }: { receptionId: number; receptionName: string; onBack: () => void }) {
  const { t, i18n } = useTranslation();
  const [date, setDate] = useState<string | null>(null);
  const { printedAt, print } = usePrint();
  const list = useQuery({ queryKey: ['business-day', 'reports', receptionId], queryFn: () => listReports(receptionId) });
  const detail = useQuery({
    queryKey: ['business-day', 'report', receptionId, date],
    queryFn: () => getArchivedReport(receptionId, date as string),
    enabled: date !== null,
  });
  const report = detail.data?.report;

  return (
    <Stack data-testid="report-archive">
      <Group justify="space-between" className="report-noprint">
        <Title order={3}>{t('businessDay.archiveTitle')}</Title>
        <Group>
          {date !== null && (
            <Button variant="default" disabled={!report} onClick={print}>{t('businessDay.printReport')}</Button>
          )}
          <Button variant="default" onClick={date !== null ? () => setDate(null) : onBack}>
            {date !== null ? t('businessDay.archiveList') : t('businessDay.backToClose')}
          </Button>
        </Group>
      </Group>

      {date === null && (
        <>
          {list.isPending && <Loader size="sm" />}
          {list.isError && <Alert color="red">{errorText(t, list.error)}</Alert>}
          {list.data && list.data.length === 0 && <Text c="dimmed" data-testid="archive-empty">{t('businessDay.archiveEmpty')}</Text>}
          {list.data && list.data.length > 0 && (
            <Paper withBorder radius="lg" bg="white" style={{ overflow: 'hidden' }}>
              {list.data.map((item) => (
                <Group key={item.businessDate} p="sm" justify="space-between" data-testid="archive-item" style={{ borderTop: '1px solid #EEF3F8' }}>
                  <Text fw={500}>{item.businessDate ? fmtDay(item.businessDate, i18n.language) : ''}</Text>
                  <Button variant="subtle" onClick={() => setDate(item.businessDate ?? null)}>{t('businessDay.archiveOpen')}</Button>
                </Group>
              ))}
            </Paper>
          )}
        </>
      )}

      {date !== null && detail.isPending && <Loader size="sm" />}
      {date !== null && detail.isError && <Alert color="red">{errorText(t, detail.error)}</Alert>}
      {date !== null && report && (
        <>
          <Text size="sm" c="dimmed" className="report-noprint" data-testid="archive-readonly">{t('businessDay.archiveReadonly')}</Text>
          <PrintableReport report={report} receptionName={receptionName} printedAt={printedAt} />
        </>
      )}
    </Stack>
  );
}
