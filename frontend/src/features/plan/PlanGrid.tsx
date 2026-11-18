import { observeElementRect, useVirtualizer, type Rect } from '@tanstack/react-virtual';
import { memo, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { colorKeyOf, type BarColors, type ColorKey } from './colors';
import { DAY_WIDTH, type BarVM, type PlanVM, type Zoom } from './types';

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

const HATCH = 'repeating-linear-gradient(135deg, rgba(74,93,117,.22) 0 3px, transparent 3px 7px)';

export interface BarClick {
  bar: BarVM;
  roomId: number;
  bedId: number;
}

interface BarViewProps {
  bar: BarVM;
  dayWidth: number;
  colors: BarColors;
  roomId: number;
  bedId: number;
  selected: boolean;
  onSelect: (click: BarClick) => void;
}

/** Pásik od polovice dňa príchodu po polovicu dňa odchodu (orezaný na okno plánu). */
const BarView = memo(function BarView({ bar, dayWidth, colors, roomId, bedId, selected, onSelect }: BarViewProps) {
  const left = (bar.startCol + (bar.clippedStart ? 0 : 0.5)) * dayWidth;
  const right = (bar.endCol + (bar.clippedEnd ? 1 : 0.5)) * dayWidth;
  const held = bar.type === 'HELD_BED';
  return (
    <button
      type="button"
      data-testid="plan-bar"
      data-bar-id={bar.id}
      data-bar-type={bar.type}
      data-start-col={bar.startCol}
      data-end-col={bar.endCol}
      aria-pressed={selected}
      aria-label={[bar.label ?? bar.reason ?? bar.type, bar.from, bar.to].join(' ')}
      onClick={() => onSelect({ bar, roomId, bedId })}
      style={{
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
        cursor: 'pointer',
        color: colors.text,
        background: held ? `${HATCH}, ${colors.fill}` : colors.fill,
        border: `${selected ? 2 : 1}px ${held ? 'dashed' : 'solid'} ${selected ? '#13243A' : colors.border}`,
        borderRadius: `${bar.clippedStart ? 0 : 6}px ${bar.clippedEnd ? 0 : 6}px ${bar.clippedEnd ? 0 : 6}px ${bar.clippedStart ? 0 : 6}px`,
      }}
    >
      {bar.type === 'STAY' ? bar.label : bar.type === 'ROOM_BLOCK' ? bar.reason : null}
      {bar.locked ? ' 🔒' : ''}
    </button>
  );
});

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
}

/** Je lôžko v daný deň (noc `day`) voľné podľa lokálnych dát? Rozhoduje server, toto je len pomoc. */
const isFree = (bars: BarVM[], day: string) => !bars.some((b) => b.from <= day && day < b.to);

export function PlanGrid({ vm, zoom, colors, dataFrom, dataTo, receptionId, selectedBarKey, onSelectBar, onFreeCell }: Props) {
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
            const isRoom = row.kind === 'room';
            return (
              <div
                key={isRoom ? `r${row.roomId}` : `b${row.bedId}`}
                role="row"
                data-testid={isRoom ? 'plan-room-row' : 'plan-bed-row'}
                data-room-id={row.roomId}
                data-bed-id={isRoom ? undefined : row.bedId}
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: LEFT_WIDTH + width,
                  height: ROW_HEIGHT,
                  transform: `translateY(${item.start - HEADER_HEIGHT}px)`,
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
                    background: isRoom ? '#DCEBFA' : '#FFFFFF',
                    borderRight: '1px solid #C9DCEF',
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
                  data-testid={isRoom ? undefined : 'plan-bed-cells'}
                  onClick={
                    !isRoom && onFreeCell
                      ? (e) => {
                          if (e.target !== e.currentTarget) return; // klik na pásik rieši pásik
                          const col = Math.floor((e.clientX - e.currentTarget.getBoundingClientRect().left) / dayWidth);
                          const day = vm.days[col];
                          if (day && isFree(row.bars, day)) {
                            onFreeCell({ roomId: row.roomId, bedId: row.bedId, day });
                          }
                        }
                      : undefined
                  }
                  style={{ position: 'relative', width, flex: 'none', backgroundImage: isRoom ? undefined : gridLines, cursor: !isRoom && onFreeCell ? 'copy' : undefined }}
                >
                  {!isRoom &&
                    row.bars.map((bar) => (
                      <BarView
                        key={bar.id}
                        bar={bar}
                        dayWidth={dayWidth}
                        colors={colors[colorKeyOf(bar)]}
                        roomId={row.roomId}
                        bedId={row.bedId}
                        selected={selectedBarKey === barKey(row.bedId, bar.id)}
                        onSelect={onSelectBar}
                      />
                    ))}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
