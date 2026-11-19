import { useDraggable, useDroppable } from '@dnd-kit/core';
import { observeElementRect, useVirtualizer, type Rect } from '@tanstack/react-virtual';
import { memo, useEffect, useLayoutEffect, useMemo, useRef, type CSSProperties } from 'react';
import { useTranslation } from 'react-i18next';
import { colorKeyOf, type BarColors, type ColorKey } from './colors';
import { canTouch, isRangeFree, type MovePerms, type Occupied } from './moves';
import { DAY_WIDTH, type BarVM, type PlanRow, type PlanVM, type Zoom } from './types';

/** Jednoznačný kľúč pásika v mriežke (blokácia sa opakuje na každom lôžku). */
export const barKey = (bedId: number, barId: string) => `${bedId}:${barId}`;

export const LEFT_WIDTH = 200;
export const ROW_HEIGHT = 30;
export const HEADER_HEIGHT = 44;
const OVERSCAN = 8;
/** jsdom (testy) nemá rozmery: bez nich by virtualizácia nevykreslila nič. */
const FALLBACK_VIEWPORT_HEIGHT = 800;

const WEEKDAYS = ['ne', 'po', 'ut', 'st', 'št', 'pi', 'so'];
const weekday = (day: string) => new Date(`${day}T00:00:00Z`).getUTCDay();
const isWeekend = (day: string) => [0, 6].includes(weekday(day));

/** Ikony pásika: príchod dnes, odchod dnes, poznámka. */
const ICON_GLYPH = { arrival: '⇥', departure: '↦', note: '✎' } as const;

const HATCH = 'repeating-linear-gradient(135deg, rgba(74,93,117,.22) 0 3px, transparent 3px 7px)';

export interface BarClick {
  bar: BarVM;
  roomId: number;
  bedId: number;
}

/** Ťahaný pásik: slúži na vyznačenie, či je cieľ voľný. */
export interface ActiveDrag {
  bar: BarVM;
  stayId: number;
}

/** Spoločné nastavenie ťahania (jedna stabilná hodnota pre všetky riadky). */
export interface DndConfig {
  perms: MovePerms;
  drag: ActiveDrag | null;
  /** Lôžka už zabraté pobytmi z odkladiska. */
  occupied: Occupied[];
}

/** Otvorenie menu pásika na súradniciach (pravé tlačidlo myši alebo kláves Menu). */
export type BarMenuHandler = (click: BarClick, x: number, y: number) => void;

interface BarViewProps {
  bar: BarVM;
  dayWidth: number;
  colors: BarColors;
  roomId: number;
  bedId: number;
  selected: boolean;
  onSelect: (click: BarClick) => void;
  dnd?: DndConfig;
  onMenu?: BarMenuHandler;
  /** Obchodný deň recepcie (ikony príchod/odchod dnes). */
  businessDate?: string;
}

/** Pásik od polovice dňa príchodu po polovicu dňa odchodu (orezaný na okno plánu). */
const BarView = memo(function BarView({ bar, dayWidth, colors, roomId, bedId, selected, onSelect, dnd, onMenu, businessDate }: BarViewProps) {
  const { t } = useTranslation();
  const touch = dnd !== undefined && canTouch(bar, dnd.perms);
  const { setNodeRef, listeners, attributes, isDragging } = useDraggable({
    id: `bar:${bedId}:${bar.id}`,
    data: { kind: 'bar', bar, roomId, bedId },
    disabled: !touch,
  });
  const left = (bar.startCol + (bar.clippedStart ? 0 : 0.5)) * dayWidth;
  const right = (bar.endCol + (bar.clippedEnd ? 1 : 0.5)) * dayWidth;
  const held = bar.type === 'HELD_BED';
  const stay = bar.type === 'STAY';
  const dashed = held || (stay && colorKeyOf(bar) === 'RESERVED');
  const icons = [
    stay && bar.status === 'RESERVED' && businessDate !== undefined && bar.from === businessDate ? 'arrival' : null,
    stay && bar.status === 'CHECKED_IN' && businessDate !== undefined && bar.to === businessDate ? 'departure' : null,
    stay && bar.hasNote ? 'note' : null,
  ].filter((x): x is 'arrival' | 'departure' | 'note' => x !== null);
  const openMenu = (x: number, y: number) => onMenu?.({ bar, roomId, bedId }, x, y);
  const right4 = Math.max(left + 4, right);
  const button = (
    <button
      ref={setNodeRef}
      {...(touch ? listeners : {})}
      {...(touch ? attributes : {})}
      type="button"
      data-testid="plan-bar"
      data-bar-id={bar.id}
      data-bar-type={bar.type}
      data-start-col={bar.startCol}
      data-end-col={bar.endCol}
      aria-pressed={selected}
      data-bar-status={bar.status}
      data-color-key={colorKeyOf(bar)}
      aria-label={[bar.label ?? bar.reason ?? bar.type, bar.from, bar.to].join(' ')}
      onClick={() => onSelect({ bar, roomId, bedId })}
      onContextMenu={
        onMenu && stay
          ? (e) => {
              e.preventDefault();
              openMenu(e.clientX, e.clientY);
            }
          : undefined
      }
      onKeyDown={
        onMenu && stay
          ? (e) => {
              if (e.key === 'ContextMenu' || (e.shiftKey && e.key === 'F10')) {
                e.preventDefault();
                const r = e.currentTarget.getBoundingClientRect();
                openMenu(r.left, r.bottom);
              }
            }
          : undefined
      }
      style={{
        opacity: isDragging ? 0.35 : 1,
        touchAction: touch ? 'none' : undefined,
        position: 'absolute',
        top: 3,
        height: ROW_HEIGHT - 7,
        left,
        width: Math.max(4, right - left),
        boxSizing: 'border-box',
        padding: '0 6px',
        overflow: 'hidden',
        whiteSpace: 'nowrap',
        textOverflow: 'ellipsis',
        textAlign: 'left',
        font: 'inherit',
        fontSize: 12,
        lineHeight: `${ROW_HEIGHT - 9}px`,
        cursor: touch ? 'grab' : 'pointer',
        color: colors.text,
        background: held ? `${HATCH}, ${colors.fill}` : colors.fill,
        border: `${selected ? 2 : 1}px ${dashed ? 'dashed' : 'solid'} ${selected ? '#13243A' : colors.border}`,
        borderRadius: `${bar.clippedStart ? 0 : 6}px ${bar.clippedEnd ? 0 : 6}px ${bar.clippedEnd ? 0 : 6}px ${bar.clippedStart ? 0 : 6}px`,
      }}
    >
      {bar.type === 'STAY' ? bar.label : bar.type === 'ROOM_BLOCK' ? bar.reason : null}
      {bar.locked ? ' 🔒' : ''}
      {icons.map((i) => (
        <span key={i} data-testid={`bar-icon-${i}`} title={t(`plan.icons.${i}`)} aria-label={t(`plan.icons.${i}`)} style={{ marginLeft: 4, fontWeight: 700 }}>
          {ICON_GLYPH[i]}
        </span>
      ))}
    </button>
  );
  if (!touch || bar.clippedEnd) return button;
  return (
    <>
      {button}
      <ResizeHandle bar={bar} bedId={bedId} roomId={roomId} left={right4 - 6} />
    </>
  );
});

/** Pravý okraj pásika: ťahaním sa mení koniec pobytu (UC10). Samostatný prvok, aby nespúšťal ťahanie celého pásika. */
function ResizeHandle({ bar, bedId, roomId, left }: { bar: BarVM; bedId: number; roomId: number; left: number }) {
  const { setNodeRef, listeners, attributes, transform } = useDraggable({
    id: `resize:${bedId}:${bar.id}`,
    data: { kind: 'resize', bar, roomId, bedId },
  });
  const style: CSSProperties = {
    position: 'absolute',
    top: 3,
    left,
    width: 8,
    height: ROW_HEIGHT - 7,
    cursor: 'ew-resize',
    touchAction: 'none',
    zIndex: 2,
    background: 'rgba(19,36,58,.25)',
    borderRadius: 3,
    transform: transform ? `translate3d(${transform.x}px,0,0)` : undefined,
  };
  return <span ref={setNodeRef} {...listeners} {...attributes} role="separator" aria-label="⇔" data-testid="resize-handle" data-bar-id={bar.id} style={style} />;
}

interface Props {
  vm: PlanVM;
  zoom: Zoom;
  colors: Record<ColorKey, BarColors>;
  /** Dni mimo `dataFrom..dataTo` nemajú lokálne dáta (šrafovanie). */
  dataFrom: string;
  dataTo: string;
  receptionId?: number;
  selectedBarKey?: string;
  /** Musí byť stabilná (useCallback), inak sa pásiky zbytočne prekresľujú. */
  onSelectBar: (click: BarClick) => void;
  /** Klik na voľný deň lôžka (UC2); bez nej sú prázdne miesta neaktívne. */
  onFreeCell?: (click: { roomId: number; bedId: number; day: string }) => void;
  /** Ťahanie (len online a s oprávnením); musí byť stabilná hodnota (useMemo). */
  dnd?: DndConfig;
  /** Menu pásika pobytu (akcie stavu, presuny); musí byť stabilná funkcia. */
  onBarMenu?: BarMenuHandler;
  /** Izba z odkazu `?roomId`: mriežka na ňu raz odroluje a zvýrazní ju. */
  focusRoomId?: number;
  /** Pozícia rolovania obnovená z pamäte relácie (po návrate z karty). */
  initialScroll?: { top: number; left: number };
  onScrollPos?: (top: number, left: number) => void;
}

/** Je lôžko v daný deň (noc `day`) voľné podľa lokálnych dát? Rozhoduje server, toto je len pomoc. */
const isFree = (bars: BarVM[], day: string) => !bars.some((b) => b.from <= day && day < b.to);

export function PlanGrid({ vm, zoom, colors, dataFrom, dataTo, receptionId, selectedBarKey, onSelectBar, onFreeCell, dnd, onBarMenu, focusRoomId, initialScroll, onScrollPos }: Props) {
  const { t } = useTranslation();
  const scrollRef = useRef<HTMLDivElement>(null);
  const dayWidth = DAY_WIDTH[zoom];
  const width = vm.days.length * dayWidth;

  const virtualizer = useVirtualizer({
    count: vm.rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: OVERSCAN,
    scrollMargin: HEADER_HEIGHT,
    initialRect: { width: 1200, height: FALLBACK_VIEWPORT_HEIGHT },
    observeElementRect: (instance, cb) =>
      observeElementRect(instance, (r: Rect) => cb(r.height > 0 ? r : { width: r.width, height: FALLBACK_VIEWPORT_HEIGHT })),
  });

  useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && initialScroll) {
      el.scrollTop = initialScroll.top;
      el.scrollLeft = initialScroll.left;
    }
    // len pri pripojení
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Odkaz na izbu: riadok môže byť mimo vykresleného výseku (virtualizácia), preto roluje virtualizér, nie DOM.
  const scrolledTo = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (focusRoomId === undefined || scrolledTo.current === focusRoomId) return;
    const index = vm.rows.findIndex((r) => r.kind === 'room' && r.roomId === focusRoomId);
    if (index < 0) return;
    // až po dokončení vykreslenia: virtualizér pri (opakovanom) pripojení vráti posun na uložený offset,
    // napr. v React StrictMode, ktorý vo vývoji spúšťa efekty dvakrát
    const frame = requestAnimationFrame(() => {
      scrolledTo.current = focusRoomId;
      virtualizer.scrollToIndex(index, { align: 'center' });
    });
    return () => cancelAnimationFrame(frame);
  }, [focusRoomId, vm.rows, virtualizer]);

  const businessCol = useMemo(() => {
    const date = receptionId === undefined ? undefined : vm.businessDates[receptionId];
    const i = date ? vm.days.indexOf(date) : -1;
    return i;
  }, [vm, receptionId]);

  // Súvislé úseky dní bez lokálnych dát
  const noData = useMemo(() => {
    const ranges: { start: number; end: number }[] = [];
    vm.days.forEach((d, i) => {
      if (d >= dataFrom && d <= dataTo) return;
      const last = ranges[ranges.length - 1];
      if (last && last.end === i - 1) last.end = i;
      else ranges.push({ start: i, end: i });
    });
    return ranges;
  }, [vm.days, dataFrom, dataTo]);

  const totalHeight = virtualizer.getTotalSize() - HEADER_HEIGHT;
  const gridLines = `repeating-linear-gradient(90deg, #E1ECF7 0 1px, transparent 1px ${dayWidth}px)`;

  return (
    <div
      ref={scrollRef}
      onScroll={onScrollPos && ((e) => onScrollPos(e.currentTarget.scrollTop, e.currentTarget.scrollLeft))}
      data-testid="plan-grid"
      role="grid"
      aria-label={t('plan.title')}
      style={{ position: 'relative', overflow: 'auto', height: '68vh', minHeight: 320, border: '1px solid #C9DCEF', borderRadius: 12, background: '#FFFFFF' }}
    >
      <div style={{ width: LEFT_WIDTH + width, position: 'relative' }}>
        {/* hlavička dní: prilepená hore */}
        <div style={{ position: 'sticky', top: 0, zIndex: 4, display: 'flex', height: HEADER_HEIGHT, background: '#F5F9FE', borderBottom: '1px solid #C9DCEF' }}>
          <div style={{ position: 'sticky', left: 0, zIndex: 5, width: LEFT_WIDTH, flex: 'none', boxSizing: 'border-box', padding: '12px 10px', fontWeight: 600, background: '#F5F9FE', borderRight: '1px solid #C9DCEF' }}>
            {t('plan.roomsColumn')}
          </div>
          {vm.days.map((d, i) => (
            <div
              key={d}
              data-testid="plan-day"
              data-day={d}
              title={d}
              style={{
                width: dayWidth,
                flex: 'none',
                boxSizing: 'border-box',
                textAlign: 'center',
                fontSize: 11,
                lineHeight: '15px',
                paddingTop: 6,
                color: '#4A5D75',
                background: i === businessCol ? '#D3E6FA' : isWeekend(d) ? '#E8EFF8' : undefined,
                fontWeight: i === businessCol ? 700 : 400,
              }}
            >
              <div>{WEEKDAYS[weekday(d)]}</div>
              <div style={{ color: '#13243A', fontSize: 12 }}>{Number(d.slice(8))}</div>
            </div>
          ))}
        </div>

        {/* telo: virtualizované riadky */}
        <div style={{ position: 'relative', height: totalHeight }}>
          <div
            aria-hidden
            style={{ position: 'absolute', top: 0, left: LEFT_WIDTH, width, height: totalHeight, pointerEvents: 'none', zIndex: 0 }}
          >
            {vm.days.map((d, i) =>
              isWeekend(d) ? (
                <div key={d} data-testid="plan-weekend" data-day={d} style={{ position: 'absolute', top: 0, bottom: 0, left: i * dayWidth, width: dayWidth, background: 'rgba(30,95,168,.06)' }} />
              ) : null,
            )}
            {noData.map((r) => (
              <div
                key={r.start}
                data-testid="plan-no-data"
                style={{ position: 'absolute', top: 0, bottom: 0, left: r.start * dayWidth, width: (r.end - r.start + 1) * dayWidth, background: HATCH, overflow: 'hidden' }}
              >
                <span style={{ position: 'sticky', top: 8, display: 'block', padding: '8px 4px', fontSize: 11, color: '#4A5D75', background: 'rgba(255,255,255,.7)' }}>
                  {t('plan.noData')}
                </span>
              </div>
            ))}
            {businessCol >= 0 && (
              <div data-testid="business-day-line" data-col={businessCol} style={{ position: 'absolute', top: 0, bottom: 0, left: businessCol * dayWidth, width: 2, background: '#B4540A' }} />
            )}
          </div>

          {virtualizer.getVirtualItems().map((item) => {
            const row = vm.rows[item.index];
            return (
              <PlanRowView
                key={row.kind === 'room' ? `r${row.roomId}` : `b${row.bedId}`}
                row={row}
                top={item.start - HEADER_HEIGHT}
                days={vm.days}
                dayWidth={dayWidth}
                width={width}
                gridLines={gridLines}
                colors={colors}
                selectedBarId={row.kind === 'bed' && selectedBarKey?.startsWith(`${row.bedId}:`) ? selectedBarKey.slice(String(row.bedId).length + 1) : undefined}
                onSelectBar={onSelectBar}
                onFreeCell={onFreeCell}
                dnd={dnd}
                onBarMenu={onBarMenu}
                businessDate={receptionId === undefined ? undefined : vm.businessDates[receptionId]}
                focused={row.kind === 'room' && row.roomId === focusRoomId}
              />
            );
          })}
        </div>
      </div>
    </div>
  );
}

interface RowProps {
  row: PlanRow;
  top: number;
  days: string[];
  dayWidth: number;
  width: number;
  gridLines: string;
  colors: Record<ColorKey, BarColors>;
  selectedBarId?: string;
  onSelectBar: (click: BarClick) => void;
  onFreeCell?: Props['onFreeCell'];
  dnd?: DndConfig;
  onBarMenu?: BarMenuHandler;
  businessDate?: string;
  /** Izba z odkazu `?roomId` (zvýraznený názov). */
  focused?: boolean;
}

/** Riadok mriežky: `memo`, takže zmena v inom riadku (ťahanie, výber) ho neprekreslí. */
const PlanRowView = memo(function PlanRowView({ row, top, days, dayWidth, width, gridLines, colors, selectedBarId, onSelectBar, onFreeCell, dnd, onBarMenu, businessDate, focused }: RowProps) {
  const { t } = useTranslation();
  const isRoom = row.kind === 'room';
  const bedId = row.kind === 'bed' ? row.bedId : 0;
  const { setNodeRef, isOver } = useDroppable({ id: `bed:${bedId}`, data: { kind: 'bed', roomId: row.roomId, bedId }, disabled: isRoom || !dnd });

  // Vyznačenie cieľa počas ťahania: podľa lokálnych dát voľné (zelená) alebo obsadené (červená)
  let hint: { left: number; width: number; free: boolean } | null = null;
  if (row.kind === 'bed' && dnd?.drag && isOver) {
    const { bar, stayId } = dnd.drag;
    const free = isRangeFree(row.bars, bar.from, bar.to, stayId, dnd.occupied, row.bedId);
    const start = Math.max(0, days.findIndex((d) => d >= bar.from));
    const endIdx = days.findIndex((d) => d >= bar.to);
    const end = endIdx < 0 ? days.length : endIdx;
    hint = { left: start * dayWidth, width: Math.max(dayWidth, (end - start) * dayWidth), free };
  }

  return (
    <div
      role="row"
      data-testid={isRoom ? 'plan-room-row' : 'plan-bed-row'}
      data-room-id={row.roomId}
      data-bed-id={isRoom ? undefined : row.bedId}
      data-focused={focused ? 'true' : undefined}
      style={{
        position: 'absolute',
        top: 0,
        left: 0,
        width: LEFT_WIDTH + width,
        height: ROW_HEIGHT,
        transform: `translateY(${top}px)`,
        display: 'flex',
        zIndex: 1,
        borderBottom: '1px solid #E1ECF7',
        background: isRoom ? 'rgba(220,235,250,.55)' : undefined,
      }}
    >
      <div
        style={{
          position: 'sticky',
          left: 0,
          zIndex: 3,
          width: LEFT_WIDTH,
          flex: 'none',
          boxSizing: 'border-box',
          padding: isRoom ? '0 10px' : '0 10px 0 28px',
          lineHeight: `${ROW_HEIGHT - 1}px`,
          fontSize: 13,
          fontWeight: isRoom ? 600 : 400,
          color: isRoom ? '#13243A' : '#4A5D75',
          background: focused ? '#FFE8A3' : isRoom ? '#DCEBFA' : '#FFFFFF',
          borderRight: '1px solid #C9DCEF',
          boxShadow: focused ? 'inset 4px 0 0 #B4540A' : undefined,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {isRoom
          ? `${row.building} ${row.label}${row.category ? ` · ${row.category}` : ''}`
          : row.extra
            ? t('plan.extraBed', { n: row.bedLabel })
            : t('plan.bed', { n: row.bedLabel })}
      </div>
      <div
        ref={isRoom ? undefined : setNodeRef}
        data-testid={isRoom ? undefined : 'plan-bed-cells'}
        onClick={
          row.kind === 'bed' && onFreeCell
            ? (e) => {
                if (e.target !== e.currentTarget) return; // klik na pásik rieši pásik
                const col = Math.floor((e.clientX - e.currentTarget.getBoundingClientRect().left) / dayWidth);
                const day = days[col];
                if (day && isFree(row.bars, day)) {
                  onFreeCell({ roomId: row.roomId, bedId: row.bedId, day });
                }
              }
            : undefined
        }
        style={{ position: 'relative', width, flex: 'none', backgroundImage: isRoom ? undefined : gridLines, cursor: !isRoom && onFreeCell ? 'copy' : undefined }}
      >
        {hint && (
          <div
            data-testid="drop-hint"
            data-free={hint.free}
            style={{ position: 'absolute', top: 1, bottom: 1, left: hint.left, width: hint.width, pointerEvents: 'none', borderRadius: 6, background: hint.free ? 'rgba(46,160,67,.28)' : 'rgba(207,34,46,.28)', outline: `2px solid ${hint.free ? '#2EA043' : '#CF222E'}` }}
          />
        )}
        {row.kind === 'bed' &&
          row.bars.map((bar) => (
            <BarView
              key={bar.id}
              bar={bar}
              dayWidth={dayWidth}
              colors={colors[colorKeyOf(bar)]}
              roomId={row.roomId}
              bedId={row.bedId}
              selected={selectedBarId === bar.id}
              onSelect={onSelectBar}
              dnd={dnd}
              onMenu={onBarMenu}
              businessDate={businessDate}
            />
          ))}
      </div>
    </div>
  );
});
