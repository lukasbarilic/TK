import { DndContext, DragOverlay, PointerSensor, pointerWithin, useSensor, useSensors, type DragEndEvent, type DragStartEvent } from '@dnd-kit/core';
import { Alert, Group, Modal, NativeSelect, Paper, Switch, Text, Title, Button, Loader } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { components } from '../../shared/api/schema';
import { queryKeys } from '../../shared/api/queryKeys';
import { DataAge, useConnection } from '../../shared/offline';
import { hasPermissionCode, receptionsInScope } from '../../shared/offline/scope';
import { ApiError } from '../../shared/api/errors';
import { ConfirmWarningsDialog } from '../../shared/ui/ConfirmWarningsDialog';
import { addDays } from '../../shared/time';
import { BarMenu, type MenuAction } from './BarMenu';
import { MoveFromDialog } from './MoveFromDialog';
import {
  batchFromParking,
  canTouch,
  failedStayId,
  hideParked,
  isBackdated,
  moveFromDrop,
  moveStart,
  occupiedByParking,
  parkingReducer,
  resizeFromDrag,
  type MoveCmd,
  type MovePerms,
} from './moves';
import { cancelStay, moveBatch, moveStay, resizeStay, swapStays } from './movesApi';
import { ParkingPanel } from './ParkingPanel';
import { useMoveActions } from './useMoveActions';
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
import { barKey, PlanGrid, type ActiveDrag, type BarClick, type DndConfig } from './PlanGrid';
import { StayPanel } from './StayPanel';
import { focusOffset } from './focus';
import { DAY_WIDTH, ZOOMS, type BarVM, type DefaultView, type PlanFilters } from './types';
import { planDataKey, usePlanLive } from './usePlanLive';

type Me = components['schemas']['MeView'];

const SAVE_DELAY_MS = 400;
const DEFAULT_VIEW_KEY = 'plan.defaultView';

/** Plán ubytovania (DLD kap. 9): mriežka, filtre, bočný panel, živé zmeny. Načíta nastavenia a až potom zobrazí obrazovku. */
export interface PlanFocus {
  roomId?: number;
  date?: string;
}

/** Voľné lôžko a deň, na ktoré recepčná klikla (UC2). */
export interface FreeCellTarget {
  receptionId?: number;
  roomId: number;
  bedId: number;
  roomLabel: string;
  bedLabel: string;
  day: string;
}

/** Skladá dialóg ubytovania z plánu (features sa medzi sebou neimportujú, dialóg dodáva `app/`). */
export type RenderCheckIn = (target: FreeCellTarget, onClose: () => void) => ReactNode;

export function PlanPage({ me, focus, renderCheckIn }: { me: Me | null | undefined; focus?: PlanFocus; renderCheckIn?: RenderCheckIn }) {
  const view = useQuery({ queryKey: queryKeys.preference(DEFAULT_VIEW_KEY), queryFn: () => loadPreference(DEFAULT_VIEW_KEY) });
  const colors = useQuery({ queryKey: queryKeys.preference('plan.colors'), queryFn: () => loadPreference('plan.colors') });
  const extra = useQuery({ queryKey: queryKeys.preference('plan.showExtraBeds'), queryFn: () => loadPreference('plan.showExtraBeds') });
  if (view.isPending || colors.isPending || extra.isPending) return <Loader data-testid="plan-loading" />;
  return (
    <PlanScreen
      me={me}
      focus={focus}
      renderCheckIn={renderCheckIn}
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
  renderCheckIn?: RenderCheckIn;
  initial: DefaultView;
  initialVersion?: number;
  colorPref: unknown;
  showExtraBeds: boolean;
}

function PlanScreen({ me, focus, renderCheckIn, initial, initialVersion, colorPref, showExtraBeds }: ScreenProps) {
  const { t } = useTranslation();
  const { state, canWrite } = useConnection();
  const [filters, setFilters] = useState<DefaultView>(initial);
  const [selected, setSelected] = useState<BarClick | null>(null);
  const [freeCell, setFreeCell] = useState<{ roomId: number; bedId: number; day: string } | null>(null);

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
  // odkaz z karty (`?date`): zobrazený rozsah sa posunie na požadovaný dátum; uložený pohľad sa nemení
  const focusDate = focus?.date;
  const focused = useRef(false);
  const [offset, setOffset] = useState(0);
  useEffect(() => {
    if (!focusDate || !businessDate || focused.current) return;
    focused.current = true;
    setOffset(focusOffset(businessDate, focusDate, filters.daysBack, filters.daysAhead));
  }, [focusDate, businessDate, filters.daysBack, filters.daysAhead]);
  const win = useMemo(
    () => (businessDate && !config.isPending ? syncWindow(businessDate, config.data?.data) : undefined),
    [businessDate, config.isPending, config.data],
  );
  const range = useMemo(
    () =>
      businessDate
        ? { from: addDays(businessDate, offset - filters.daysBack), to: addDays(businessDate, offset + filters.daysAhead) }
        : undefined,
    [businessDate, offset, filters.daysBack, filters.daysAhead],
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

  const canCheckIn = renderCheckIn !== undefined && canWrite && hasPermissionCode(me, 'STAY_CREATE');
  const cellRoom = freeCell && vm?.rows.find((r) => r.kind === 'room' && r.roomId === freeCell.roomId);
  const cellBed = freeCell && vm?.rows.find((r) => r.kind === 'bed' && r.bedId === freeCell.bedId);

  const selectedRoom = selected && vm?.rows.find((r) => r.kind === 'room' && r.roomId === selected.roomId);
  const selectedBed = selected && vm?.rows.find((r) => r.kind === 'bed' && r.bedId === selected.bedId);

  // --- zmeny ubytovania: ťahanie, odkladisko, kontextové menu (DLD kap. 9.3) ---
  const businessDate0 = receptionId === undefined ? undefined : vm?.businessDates[receptionId];
  const perms = useMemo<MovePerms>(
    () => ({
      move: canWrite && hasPermissionCode(me, 'STAY_MOVE'),
      moveLocked: hasPermissionCode(me, 'STAY_MOVE_LOCKED'),
      backdate: hasPermissionCode(me, 'STAY_BACKDATE'),
      businessDate: businessDate0,
    }),
    [canWrite, me, businessDate0],
  );
  const moves = useMoveActions(receptionId);
  const [parked, dispatchParking] = useReducer(parkingReducer, []);
  const [drag, setDrag] = useState<ActiveDrag | null>(null);
  const [menu, setMenu] = useState<{ click: BarClick; x: number; y: number } | null>(null);
  const [moveFrom, setMoveFrom] = useState<BarClick | null>(null);
  const [swapFirst, setSwapFirst] = useState<BarClick | null>(null);
  const [cancelTarget, setCancelTarget] = useState<BarClick | null>(null);
  const [failed, setFailed] = useState<number | undefined>();
  const [localNotice, setLocalNotice] = useState<string | null>(null);
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 6 } }));
  const dayWidth = DAY_WIDTH[filters.zoom];

  const shownVm = useMemo(() => (vm ? { ...vm, rows: hideParked(vm.rows, parked) } : undefined), [vm, parked]);
  const bedLabels = useMemo(() => {
    const m = new Map<number, { roomId: number; label: string }>();
    let room = '';
    for (const r of vm?.rows ?? []) {
      if (r.kind === 'room') room = `${r.building} ${r.label}`;
      else m.set(r.bedId, { roomId: r.roomId, label: `${room} · ${r.extra ? 'P' : 'L'}${r.bedLabel}` });
    }
    return m;
  }, [vm]);
  const roomOfBed = useCallback((bedId: number) => bedLabels.get(bedId)?.roomId, [bedLabels]);

  const openMenu = useCallback((click: BarClick, x: number, y: number) => setMenu({ click, x, y }), []);
  const dnd = useMemo<DndConfig | undefined>(
    () => (perms.move ? { perms, drag, occupied: occupiedByParking(parked, drag?.stayId), onBarMenu: openMenu } : undefined),
    [perms, drag, parked, openMenu],
  );

  useEffect(() => {
    if (parked.length === 0) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [parked.length]);

  const runMove = (bar: BarVM, originBedId: number, cmd: MoveCmd) => {
    if (isBackdated(moveStart(bar, cmd), perms)) return setLocalNotice(t('plan.moves.notAllowedHere'));
    setLocalNotice(null);
    const rooms = [roomOfBed(originBedId), roomOfBed(cmd.targetBedId)].filter((x): x is number => x !== undefined);
    void moves.run({ roomIds: rooms, run: (c) => moveStay(cmd, c) });
  };

  const onDragStart = (e: DragStartEvent) => {
    const d = e.active.data.current as { kind: string; bar?: BarVM; item?: { stayId: number; label: string; from: string; to: string; originBedId: number } } | undefined;
    if (d?.kind === 'bar' && d.bar?.stayId !== undefined) setDrag({ bar: d.bar, stayId: d.bar.stayId });
    if (d?.kind === 'parked' && d.item) {
      const i = d.item;
      setDrag({
        stayId: i.stayId,
        bar: { id: `stay:${i.stayId}`, type: 'STAY', bedId: i.originBedId, from: i.from, to: i.to, startCol: 0, endCol: 0, clippedStart: false, clippedEnd: false, label: i.label, stayId: i.stayId },
      });
    }
  };

  const onDragEnd = (e: DragEndEvent) => {
    setDrag(null);
    const d = e.active.data.current as { kind: string; bar?: BarVM; roomId?: number; bedId?: number; item?: { stayId: number } } | undefined;
    const over = e.over?.data.current as { kind: string; bedId?: number } | undefined;
    if (!d) return;
    if (d.kind === 'resize' && d.bar && d.roomId !== undefined) {
      const bar = d.bar;
      const to = resizeFromDrag(bar, Math.round(e.delta.x / dayWidth));
      if (!to || bar.stayId === undefined || bar.reservationId === undefined) return;
      if (isBackdated(to < bar.to ? to : bar.to, perms)) return setLocalNotice(t('plan.moves.notAllowedHere'));
      setLocalNotice(null);
      const { stayId, reservationId } = bar;
      void moves.run({ roomIds: [d.roomId], run: (c) => resizeStay(reservationId, stayId, to, c) });
      return;
    }
    if (!over) return;
    if (d.kind === 'parked' && d.item && over.kind === 'bed' && over.bedId !== undefined) {
      dispatchParking({ type: 'place', stayId: d.item.stayId, bedId: over.bedId });
      setFailed(undefined);
      return;
    }
    if (d.kind !== 'bar' || !d.bar || d.bedId === undefined || !canTouch(d.bar, perms) || d.bar.stayId === undefined) return;
    if (over.kind === 'parking') {
      if (d.bar.clippedStart || d.bar.clippedEnd || (businessDate0 && isBackdated(d.bar.from, perms))) return setLocalNotice(t('plan.moves.notAllowedHere'));
      dispatchParking({ type: 'park', item: { stayId: d.bar.stayId, label: d.bar.label ?? '', from: d.bar.from, to: d.bar.to, originBedId: d.bedId } });
      return;
    }
    if (over.kind === 'bed' && over.bedId !== undefined) {
      const act = e.activatorEvent as MouseEvent;
      const left = e.over?.rect.left ?? 0;
      const col = Math.floor((act.clientX + e.delta.x - left) / dayWidth);
      const dropDay = vm?.days[Math.min(Math.max(col, 0), (vm?.days.length ?? 1) - 1)];
      const cmd = moveFromDrop(d.bar, d.bedId, over.bedId, dropDay, act.altKey === true);
      if (cmd) runMove(d.bar, d.bedId, cmd);
    }
  };

  const saveParking = () => {
    const items = parked;
    const rooms = items.flatMap((i) => [roomOfBed(i.originBedId), i.targetBedId === undefined ? undefined : roomOfBed(i.targetBedId)]).filter((x): x is number => x !== undefined);
    setFailed(undefined);
    void moves.run({
      roomIds: rooms,
      run: (c) => moveBatch(batchFromParking(items), c),
      onSuccess: () => dispatchParking({ type: 'clear' }),
      onError: (e) => e instanceof ApiError && !e.confirmable && setFailed(failedStayId(e.detail, items)),
    });
  };

  const pickMenu = (a: MenuAction) => {
    const click = menu?.click;
    setMenu(null);
    if (!click) return;
    if (a === 'moveFrom') setMoveFrom(click);
    if (a === 'swap') setSwapFirst(click);
    if (a === 'cancel') setCancelTarget(click);
  };
  const menuActions = (c: BarClick): MenuAction[] => ['moveFrom', 'swap', ...(c.bar.status === 'RESERVED' ? (['cancel'] as const) : [])];

  const select = useCallback(
    (c: BarClick) => {
      if (swapFirst) {
        const a = swapFirst.bar.stayId;
        const b = c.bar.stayId;
        if (c.bar.type !== 'STAY' || a === undefined || b === undefined || a === b) return;
        setSwapFirst(null);
        void moves.run({ roomIds: [swapFirst.roomId, c.roomId], run: (conf) => swapStays(a, b, conf) });
        return;
      }
      setSelected(c);
    },
    [swapFirst, moves],
  );

  const notice = localNotice ?? moves.notice;

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
          <Button variant="default" size="sm" onClick={() => { setOffset(0); patch({ daysBack: 1, daysAhead: 13 }); }}>{t('plan.today')}</Button>
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
      {notice && (
        <Alert color="orange" mb="md" withCloseButton onClose={() => { setLocalNotice(null); moves.dismissNotice(); }} data-testid="plan-notice">
          {notice}
        </Alert>
      )}
      {swapFirst && (
        <Alert color="blue" mb="md" data-testid="plan-swap-hint">
          {t('plan.moves.swapPick')}{' '}
          <Button size="compact-xs" variant="subtle" onClick={() => setSwapFirst(null)}>{t('plan.moves.swapCancel')}</Button>
        </Alert>
      )}
      {noPlanAccess && <Text c="dimmed">{t('plan.noReception')}</Text>}
      {(receptions.isPending || day.isPending || plan.isPending) && receptionId !== undefined && <Loader size="sm" />}
      {plan.isError && <Alert color="red">{t('errors.generic')}</Alert>}
      {empty && <Text c="dimmed" data-testid="plan-empty">{t('offline.notStored')}</Text>}

      {vm && shownVm && stored && (
        <DndContext sensors={sensors} collisionDetection={pointerWithin} onDragStart={onDragStart} onDragEnd={onDragEnd} onDragCancel={() => setDrag(null)}>
        <Group align="flex-start" wrap="nowrap" gap="md">
          <div style={{ flex: 1, minWidth: 0 }}>
            <PlanGrid
              vm={shownVm}
              zoom={filters.zoom}
              colors={palette}
              dataFrom={stored.from}
              dataTo={stored.to}
              receptionId={receptionId}
              selectedBarKey={selected ? barKey(selected.bedId, selected.bar.id) : undefined}
              onSelectBar={select}
              onFreeCell={canCheckIn ? setFreeCell : undefined}
              dnd={dnd}
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
          {(perms.move || parked.length > 0) && (
            <ParkingPanel
              items={parked}
              targetLabel={(id) => bedLabels.get(id)?.label ?? String(id)}
              failedStayId={failed}
              saving={moves.busy}
              onRemove={(id) => dispatchParking({ type: 'remove', stayId: id })}
              onSave={saveParking}
              onClear={() => { dispatchParking({ type: 'clear' }); setFailed(undefined); }}
            />
          )}
        </Group>
        <DragOverlay dropAnimation={null}>
          {drag && (
            <div data-testid="drag-overlay" style={{ padding: '2px 8px', borderRadius: 6, background: '#DCEBFA', border: '1px solid #1E5FA8', fontSize: 12, whiteSpace: 'nowrap' }}>
              {drag.bar.label}
            </div>
          )}
        </DragOverlay>
        </DndContext>
      )}
      {menu && <BarMenu x={menu.x} y={menu.y} actions={menuActions(menu.click)} onPick={pickMenu} onClose={() => setMenu(null)} />}
      {moveFrom && moveFrom.bar.stayId !== undefined && (
        <MoveFromDialog
          beds={[...bedLabels].filter(([id]) => id !== moveFrom.bedId).map(([id, v]) => ({ value: String(id), label: v.label }))}
          from={moveFrom.bar.from}
          to={moveFrom.bar.to}
          onClose={() => setMoveFrom(null)}
          onSubmit={(bedId, day) => {
            const c = moveFrom;
            setMoveFrom(null);
            const cmd = moveFromDrop(c.bar, c.bedId, bedId, day, true);
            if (cmd) runMove(c.bar, c.bedId, cmd);
          }}
        />
      )}
      <Modal opened={cancelTarget !== null} onClose={() => setCancelTarget(null)} title={t('plan.moves.cancelTitle')} centered>
        <Text size="sm" mb="md">{t('plan.moves.cancelText')}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={() => setCancelTarget(null)}>{t('plan.moves.cancelNo')}</Button>
          <Button
            color="red"
            onClick={() => {
              const c = cancelTarget;
              setCancelTarget(null);
              if (c?.bar.stayId !== undefined) {
                const id = c.bar.stayId;
                void moves.run({ roomIds: [c.roomId], run: () => cancelStay(id) });
              }
            }}
          >
            {t('plan.moves.cancelYes')}
          </Button>
        </Group>
      </Modal>
      <ConfirmWarningsDialog warnings={moves.warnings} loading={moves.busy} onConfirm={moves.confirm} onCancel={moves.cancelConfirm} />
      {canCheckIn && freeCell && cellRoom?.kind === 'room' && cellBed?.kind === 'bed' &&
        renderCheckIn(
          { ...freeCell, receptionId, roomLabel: `${cellRoom.building} ${cellRoom.label}`, bedLabel: cellBed.bedLabel },
          () => setFreeCell(null),
        )}
    </Paper>
  );
}
