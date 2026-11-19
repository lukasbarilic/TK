import { Paper, SimpleGrid, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { DayReport } from './businessDayApi';
import { fmtDay, fmtShort, forecastOf, isWeekend, labelledIndexes, pct } from './reportFormat';

type Stats = NonNullable<NonNullable<DayReport['occupancy']>['complex']>;

function Tile({ label, value, sub, testId, subColor }: { label: string; value: string; sub?: string; testId: string; subColor?: string }) {
  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid={testId}>
      <Text size="xs" c="dimmed">{label}</Text>
      <Text fw={700} fz={28} lh={1.2}>{value}</Text>
      {sub && <Text size="xs" c={subColor ?? 'dimmed'}>{sub}</Text>}
    </Paper>
  );
}

function PercentBar({ value }: { value: number }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <div style={{ flexGrow: 1, height: 8, borderRadius: 4, background: '#E3EDF7', overflow: 'hidden' }}>
        <div style={{ height: 8, width: `${Math.min(100, Math.max(0, value))}%`, background: '#1E5FA8' }} />
      </div>
      <span style={{ width: 44, textAlign: 'right', fontWeight: 600 }}>{pct(value)}</span>
    </div>
  );
}

function ForecastChart({ report }: { report: DayReport }) {
  const { t, i18n } = useTranslation();
  const points = forecastOf(report);
  if (points.length === 0) return null;
  const labelled = labelledIndexes(points);
  const maxIdx = [...labelled].reduce((a, b) => (points[b].percent > points[a].percent ? b : a));
  const first = points[0];
  const last = points[points.length - 1];
  const summary = t('businessDay.chartSummary', {
    count: points.length,
    firstPct: pct(first.percent), firstDate: fmtDay(first.date, i18n.language),
    lastPct: pct(last.percent), lastDate: fmtShort(last.date, i18n.language),
    maxPct: pct(points[maxIdx].percent), maxDate: fmtShort(points[maxIdx].date, i18n.language),
  });
  return (
    <Paper withBorder radius="lg" bg="white" p="md" data-testid="report-forecast">
      <Text fw={600} size="lg">{t('businessDay.forecastTitle')}</Text>
      <Text size="xs" c="dimmed" mb="sm">{t('businessDay.forecastHint')}</Text>
      <div role="img" aria-label={summary} data-testid="forecast-chart" style={{ display: 'flex', alignItems: 'flex-end', gap: 2, height: 220, borderBottom: '1px solid #AFC3D8' }}>
        {points.map((p, i) => (
          <div
            key={p.date}
            data-testid="forecast-bar"
            title={t('businessDay.forecastTooltip', { date: fmtDay(p.date, i18n.language), percent: pct(p.percent) })}
            style={{ flex: '1 1 0', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'flex-end', height: 220 }}
          >
            {labelled.has(i) && <span data-testid="forecast-label" style={{ fontSize: 11, fontWeight: 600, marginBottom: 4 }}>{pct(p.percent)}</span>}
            <div style={{ width: '70%', height: Math.max(0, Math.min(100, p.percent)) * 1.8, background: '#3F79B8', borderRadius: '4px 4px 0 0' }} />
          </div>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 2, marginTop: 6 }} aria-hidden="true">
        {points.map((p) => (
          <span
            key={p.date}
            data-testid="forecast-day"
            data-weekend={isWeekend(p.date) ? 'true' : 'false'}
            style={{ flex: '1 1 0', textAlign: 'center', fontSize: 11, color: isWeekend(p.date) ? '#13243A' : '#4A5D75', fontWeight: isWeekend(p.date) ? 700 : 400 }}
          >
            {fmtShort(p.date, i18n.language)}
          </span>
        ))}
      </div>
    </Paper>
  );
}

/** Report dňa (HLD kap. 5.8): dlaždice, obsadenosť podľa budov a výhľad na 14 dní. Používa sa aj v archíve. */
export function ReportView({ report }: { report: DayReport }) {
  const { t } = useTranslation();
  const a = report.arrivals;
  const d = report.departures;
  const h = report.inHouse;
  const c = report.occupancy?.complex;
  const noShow = a?.noShowPersons ?? 0;
  const rows: { key: string; name: string; stats: Stats; total?: boolean }[] = [
    ...(report.occupancy?.buildings ?? []).map((b) => ({ key: String(b.buildingId), name: b.name ?? '', stats: b.stats ?? {} })),
    ...(c ? [{ key: 'complex', name: t('businessDay.complexTotal'), stats: c, total: true }] : []),
  ];
  const usable = (s: Stats) => (s.beds ?? 0) - (s.blocked ?? 0);
  return (
    <>
      <SimpleGrid cols={{ base: 2, md: 5 }} data-testid="report-tiles">
        <Tile testId="tile-arrivals" label={t('businessDay.tileArrivals')} value={`${a?.arrivedPersons ?? 0} / ${a?.expectedPersons ?? 0}`}
          sub={noShow > 0 ? t('businessDay.noShowCount', { count: noShow }) : t('businessDay.allArrived')} subColor={noShow > 0 ? 'orange.9' : 'teal.9'} />
        <Tile testId="tile-departures" label={t('businessDay.tileDepartures')} value={`${d?.departedPersons ?? 0} / ${d?.expectedPersons ?? 0}`}
          sub={(d?.departedPersons ?? 0) >= (d?.expectedPersons ?? 0) ? t('businessDay.allDeparted') : t('businessDay.notDeparted', { count: (d?.expectedPersons ?? 0) - (d?.departedPersons ?? 0) })}
          subColor={(d?.departedPersons ?? 0) >= (d?.expectedPersons ?? 0) ? 'teal.9' : 'orange.9'} />
        <Tile testId="tile-inhouse" label={t('businessDay.tileInHouse')} value={String(h?.endOfDay ?? 0)}
          sub={t('businessDay.inHouseSub', { start: h?.startOfDay ?? 0, arrived: a?.arrivedPersons ?? 0, departed: d?.departedPersons ?? 0 })} />
        <Tile testId="tile-occupancy" label={t('businessDay.tileOccupancy')} value={pct(c?.percent)}
          sub={t('businessDay.occupancySub', { occupied: c?.occupied ?? 0, usable: c ? usable(c) : 0 })} />
        <Tile testId="tile-free" label={t('businessDay.tileFree')} value={String(c?.free ?? 0)} sub={t('businessDay.blockedSub', { count: c?.blocked ?? 0 })} />
      </SimpleGrid>

      <Paper withBorder radius="lg" bg="white" style={{ overflow: 'hidden' }} data-testid="report-buildings">
        <Text fw={600} size="lg" p="md">{t('businessDay.buildingsTitle')}</Text>
        <Table.ScrollContainer minWidth={480}>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('businessDay.colBuilding')}</Table.Th>
                <Table.Th ta="right">{t('businessDay.colBeds')}</Table.Th>
                <Table.Th ta="right">{t('businessDay.colOccupied')}</Table.Th>
                <Table.Th ta="right">{t('businessDay.colFree')}</Table.Th>
                <Table.Th ta="right">{t('businessDay.colBlocked')}</Table.Th>
                <Table.Th w={180}>{t('businessDay.colPercent')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((r) => (
                <Table.Tr key={r.key} data-testid="building-row" fw={r.total ? 700 : undefined} bg={r.total ? '#F5F9FE' : undefined}>
                  <Table.Td>{r.name}</Table.Td>
                  <Table.Td ta="right">{r.stats.beds ?? 0}</Table.Td>
                  <Table.Td ta="right">{r.stats.occupied ?? 0}</Table.Td>
                  <Table.Td ta="right">{r.stats.free ?? 0}</Table.Td>
                  <Table.Td ta="right">{r.stats.blocked ?? 0}</Table.Td>
                  <Table.Td><PercentBar value={r.stats.percent ?? 0} /></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
        <Text size="xs" c="dimmed" p="md" data-testid="occupancy-explain">{t('businessDay.occupancyExplain')}</Text>
      </Paper>

      <ForecastChart report={report} />
    </>
  );
}
