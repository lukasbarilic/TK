import { Text } from '@mantine/core';
import { flushSync } from 'react-dom';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { DayReport } from './businessDayApi';
import { ReportView } from './ReportView';
import { fmtDay } from './reportFormat';

/** Tlač na A4 na výšku: zobrazí sa len oblasť reportu, bez menu a ovládacích prvkov. */
export const PRINT_CSS = `.report-print-head { display: none; }
@page { size: A4 portrait; margin: 12mm; }
@media print {
  body * { visibility: hidden; }
  [data-testid="report-print-area"], [data-testid="report-print-area"] * { visibility: visible; }
  [data-testid="report-print-area"] { position: absolute; left: 0; top: 0; width: 100%; }
  .report-print-head { display: block; margin-bottom: 8mm; }
  .report-noprint { display: none !important; }
}`;

export function usePrint() {
  const { i18n } = useTranslation();
  const [printedAt, setPrintedAt] = useState('');
  const print = () => {
    flushSync(() => setPrintedAt(new Date().toLocaleString(i18n.language)));
    window.print();
  };
  return { printedAt, print };
}

/** Report dňa s tlačovou hlavičkou (recepcia, deň, čas tlače); hlavička je viditeľná len v tlači. */
export function PrintableReport({ report, receptionName, printedAt }: { report: DayReport; receptionName: string; printedAt: string }) {
  const { t, i18n } = useTranslation();
  return (
    <div data-testid="report-print-area" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <style>{PRINT_CSS}</style>
      <div className="report-print-head" data-testid="report-print-head">
        <Text fw={700} size="xl">{t('businessDay.printTitle', { reception: receptionName })}</Text>
        <Text>{t('businessDay.printDay', { date: report.businessDate ? fmtDay(report.businessDate, i18n.language) : '' })}</Text>
        <Text>{t('businessDay.printedAt', { time: printedAt })}</Text>
      </div>
      <ReportView report={report} />
    </div>
  );
}
