import { Alert, Group, NativeSelect, Paper, Switch, Text, Title, Button, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { components } from '../../shared/api/schema';
import { queryKeys } from '../../shared/api/queryKeys';
import { DataAge, useConnection } from '../../shared/offline';
import { receptionsInScope } from '../../shared/offline/scope';
import { addDays, diffDays } from '../../shared/time';
import { buildPlanVM } from './buildPlanVM';
import { resolveColors } from './colors';
import {
  loadBusinessDay,
  loadClientConfig,
  loadPlan,
  loadPreference,
  loadReceptions,
  parseDefaultView,
  savePreference,
  syncWindow,
  type Preference,
} from './planApi';
import { barKey, PlanGrid, type BarClick } from './PlanGrid';
import { StayPanel } from './StayPanel';
import { ZOOMS, type DefaultView, type PlanFilters } from './types';
import { planDataKey, usePlanLive } from './usePlanLive';

type Me = components['schemas']['MeView'];

const SAVE_DELAY_MS = 400;
const DEFAULT_VIEW_KEY = 'plan.defaultView';

/** Plán ubytovania (DLD kap. 9): mriežka, filtre, bočný panel, živé zmeny. Načíta nastavenia a až potom zobrazí obrazovku. */
export interface PlanFocus {
  roomId?: number;
  date?: string;
}

export function PlanPage({ me, focus }: { me: Me | null | undefined; focus?: PlanFocus }) {
  const view = useQuery({ queryKey: queryKeys.preference(DEFAULT_VIEW_KEY), queryFn: () => loadPreference(DEFAULT_VIEW_KEY) });
  const colors = useQuery({ queryKey: queryKeys.preference('plan.colors'), queryFn: () => loadPreference('plan.colors') });
  const extra = useQuery({ queryKey: queryKeys.preference('plan.showExtraBeds'), queryFn: () => loadPreference('plan.showExtraBeds') });
  if (view.isPending || colors.isPending || extra.isPending) return <Loader data-testid="plan-loading" />;
  return (
    <PlanScreen
      me={me}
      focus={focus}
      initial={parseDefaultView(view.data?.data?.value)}
      initialVersion={view.data?.data?.version}
      colorPref={colors.data?.data?.value}
      showExtraBeds={(extra.data?.data?.value as unknown) !== false}
    />
  );
}

interface ScreenProps {
  me: Me | null | undefined;
  focus?: PlanFocus;
  initial: DefaultView;
  initialVersion?: number;
  colorPref: unknown;
  showExtraBeds: boolean;
}

function PlanScreen({ me, focus, initial, initialVersion, colorPref, showExtraBeds }: ScreenProps) {
  const { t } = useTranslation();
  const { state, canWrite } = useConnection();
  const [filters, setFilters] = useState<DefaultView>(initial);
  const [selected, setSelected] = useState<BarClick | null>(null);

  // --- recepcia ---
  const receptions = useQuery({ queryKey: [...queryKeys.plan, 'receptions'], queryFn: loadReceptions });
  const allowed = useMemo(() => {
    const list = (receptions.data?.data ?? []).filter((r) => r.id !== undefined && r.active !== false);
    const ids = receptionsInScope(me, 'PLAN_VIEW', list.map((r) => r.id as number));
    return list.filter((r) => ids.includes(r.id as number));
  }, [receptions.data, me]);
  const receptionId =
    [filters.receptionId, me?.defaultReceptionId].find((id) => id !== undefined && allowed.some((r) => r.id === id)) ??
    allowed[0]?.id;

  // --- obchodný deň, okno a plán ---
  const day = useQuery({
    queryKey: [...queryKeys.plan, 'day', receptionId],
    queryFn: () => loadBusinessDay(receptionId as number),
    enabled: receptionId !== undefined,
  });
  const config = useQuery({ queryKey: [...queryKeys.plan, 'config'], queryFn: loadClientConfig });
  const businessDate = day.data?.data?.businessDate;
  // odkaz z karty (`?date`): rozsah plánu sa rozšíri tak, aby obsahoval požadovaný dátum
  const focusDate = focus?.date;
  const focused = useRef(false);
  useEffect(() => {
    if (!focusDate || !businessDate || focused.current) return;
    focused.current = true;
    const diff = diffDays(businessDate, focusDate);
    setFilters((f) => ({ ...f, daysBack: Math.min(31, Math.max(f.daysBack, 1 - diff)), daysAhead: Math.min(60, Math.max(f.daysAhead, diff + 7)) }));
  }, [focusDate, businessDate]);
  const win = useMemo(
    () => (businessDate && !config.isPending ? syncWindow(businessDate, config.data?.data) : undefined),
    [businessDate, config.isPending, config.data],
  );
  const range = useMemo(
    () => (businessDate ? { from: addDays(businessDate, -filters.daysBack), to: addDays(businessDate, filters.daysAhead) } : undefined),
    [businessDate, filters.daysBack, filters.daysAhead],
  );
  const plan = useQuery({
    queryKey: [...planDataKey(receptionId ?? 0), range?.from, range?.to, win?.from, win?.to],
    queryFn: () => loadPlan(receptionId as number, range as { from: string; to: string }, win as { from: string; to: string }, canWrite),
    enabled: receptionId !== undefined && range !== undefined && win !== undefined,
  });
  usePlanLive(receptionId);

  // --- model zobrazenia ---
  const planFilters: PlanFilters | undefined = range && {
    receptionId,
    buildingIds: filters.buildingIds,
    from: range.from,
    to: range.to,
    freeOnly: filters.freeOnly,
    fullyFree: filters.fullyFree,
  };
  const stored = plan.data && !plan.data.empty ? plan.data.data : undefined;
  const vm = useMemo(
    () => (planFilters && stored ? buildPlanVM(stored.plan, planFilters, { showExtraBeds }) : undefined),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [stored, range?.from, range?.to, receptionId, filters.buildingIds, filters.freeOnly, filters.fullyFree, showExtraBeds],
  );
  // odkaz z karty (`?roomId`): po zobrazení plánu sa izba odroluje do pohľadu
  const focusRoom = focus?.roomId;
  const scrolledRoom = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (focusRoom === undefined || !vm || scrolledRoom.current === focusRoom) return;
    const el = document.querySelector(`[data-room-id="${focusRoom}"]`);
    if (!el) return;
    scrolledRoom.current = focusRoom;
    el.scrollIntoView({ block: 'center' });
  }, [focusRoom, vm]);
  const palette = useMemo(() => resolveColors(colorPref), [colorPref]);

  const buildings = useMemo(() => {
    const m = new Map<number, string>();
    for (const r of stored?.plan.rooms ?? []) if (r.buildingId !== undefined) m.set(r.buildingId, r.building ?? String(r.buildingId));
    return [...m].sort((a, b) => a[1].localeCompare(b[1]));
  }, [stored]);

  // --- uloženie posledného pohľadu do plan.defaultView (offline len lokálne) ---
  const version = useRef<number | undefined>(initialVersion);
  const lastSaved = useRef(JSON.stringify(initial));
  const canWriteRef = useRef(canWrite);
  useEffect(() => {
    canWriteRef.current = canWrite;
  }, [canWrite]);
  useEffect(() => {
    const json = JSON.stringify(filters);
    if (json === lastSaved.current) return;
    const timer = setTimeout(() => {
      lastSaved.current = json;
      savePreference(DEFAULT_VIEW_KEY, filters, version.current, canWriteRef.current)
        .then((p: Preference) => {
          version.current = p.version;
        })
        .catch(() => {});
    }, SAVE_DELAY_MS);
    return () => clearTimeout(timer);
  }, [filters]);

  const patch = useCallback((p: Partial<DefaultView>) => setFilters((f) => ({ ...f, ...p })), []);
  const shift = (days: number) => patch({ daysBack: filters.daysBack - days, daysAhead: filters.daysAhead + days });
  const select = useCallback((c: BarClick) => setSelected(c), []);

  const selectedRoom = selected && vm?.rows.find((r) => r.kind === 'room' && r.roomId === selected.roomId);
  const selectedBed = selected && vm?.rows.find((r) => r.kind === 'bed' && r.bedId === selected.bedId);

  const noPlanAccess = receptions.data && allowed.length === 0;
  const empty = plan.data?.empty || (!plan.isPending && !stored && !plan.isError);

  return (
    <Paper withBorder p="md" radius="lg" bg="white" data-testid="plan-page">
      <Group justify="space-between" mb="sm" align="flex-end">
        <Title order={2}>{t('plan.title')}</Title>
        {plan.data && !plan.data.empty && <DataAge source={plan.data.source} syncedAt={plan.data.syncedAt} />}
      </Group>

      <Group mb="md" gap="md" align="flex-end" wrap="wrap">
        <NativeSelect
          label={t('shell.receptionLabel')}
          data={allowed.map((r) => ({ value: String(r.id), label: r.name ?? r.code ?? String(r.id) }))}
          value={receptionId === undefined ? '' : String(receptionId)}
          onChange={(e) => patch({ receptionId: Number(e.currentTarget.value), buildingIds: [] })}
        />
        <Group gap={4}>
          <Button variant="default" size="sm" aria-label={t('plan.prevWeek')} onClick={() => shift(-7)} disabled={!range}>‹</Button>
          <Text fw={600} size="sm" data-testid="plan-range" px="xs">
            {range ? `${range.from} – ${range.to}` : '–'}
          </Text>
          <Button variant="default" size="sm" aria-label={t('plan.nextWeek')} onClick={() => shift(7)} disabled={!range}>›</Button>
          <Button variant="default" size="sm" onClick={() => patch({ daysBack: 1, daysAhead: 13 })}>{t('plan.today')}</Button>
        </Group>
        {buildings.length > 1 && (
          <Group gap={6} role="group" aria-label={t('plan.buildings')}>
            {buildings.map(([id, name]) => {
              const on = filters.buildingIds.includes(id);
              return (
                <Button
                  key={id}
                  size="compact-md"
                  radius="xl"
                  variant={on ? 'light' : 'default'}
                  aria-pressed={on}
                  onClick={() => patch({ buildingIds: on ? filters.buildingIds.filter((b) => b !== id) : [...filters.buildingIds, id] })}
                >
                  {name}
                </Button>
              );
            })}
          </Group>
        )}
        <Switch label={t('plan.freeOnly')} checked={filters.freeOnly} onChange={(e) => patch({ freeOnly: e.currentTarget.checked })} />
        <Switch label={t('plan.fullyFree')} checked={filters.fullyFree} onChange={(e) => patch({ fullyFree: e.currentTarget.checked })} />
        <Group gap={4} role="group" aria-label={t('plan.zoom.label')}>
          {ZOOMS.map((z) => (
            <Button
              key={z}
              size="compact-sm"
              variant={filters.zoom === z ? 'filled' : 'default'}
              aria-pressed={filters.zoom === z}
              onClick={() => patch({ zoom: z })}
            >
              {t(`plan.zoom.${z}`)}
            </Button>
          ))}
        </Group>
      </Group>

      {state !== 'online' && state !== 'checking' && (
        <Alert color="orange" mb="md" data-testid="plan-readonly">{t('plan.readOnly')}</Alert>
      )}
      {noPlanAccess && <Text c="dimmed">{t('plan.noReception')}</Text>}
      {(receptions.isPending || day.isPending || plan.isPending) && receptionId !== undefined && <Loader size="sm" />}
      {plan.isError && <Alert color="red">{t('errors.generic')}</Alert>}
      {empty && <Text c="dimmed" data-testid="plan-empty">{t('offline.notStored')}</Text>}

      {vm && stored && (
        <Group align="flex-start" wrap="nowrap" gap="md">
          <div style={{ flex: 1, minWidth: 0 }}>
            <PlanGrid
              vm={vm}
              zoom={filters.zoom}
              colors={palette}
              dataFrom={stored.from}
              dataTo={stored.to}
              receptionId={receptionId}
              selectedBarKey={selected ? barKey(selected.bedId, selected.bar.id) : undefined}
              onSelectBar={select}
            />
            {vm.rows.length === 0 && <Text c="dimmed" mt="sm" data-testid="plan-no-rooms">{t('plan.noRooms')}</Text>}
          </div>
          {selected && selectedRoom && selectedRoom.kind === 'room' && selectedBed && selectedBed.kind === 'bed' && (
            <StayPanel
              bar={selected.bar}
              roomLabel={`${selectedRoom.building} ${selectedRoom.label}`}
              bedLabel={selectedBed.bedLabel}
              onClose={() => setSelected(null)}
            />
          )}
        </Group>
      )}
    </Paper>
  );
}
