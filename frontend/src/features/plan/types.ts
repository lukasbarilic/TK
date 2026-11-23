import type { components } from '../../shared/api/schema';

export type Plan = components['schemas']['Plan'];

/** Plán s oknom, tak ako ho ukladá synchronizácia do trezora (dataset `plan`, DLD kap. 5.1). */
export interface StoredPlan {
  from: string;
  to: string;
  plan: Plan;
}

export type BarType = 'STAY' | 'HELD_BED' | 'ROOM_BLOCK';

export interface BarVM {
  /** `stay:812`, `held:812:12`, `block:5` (blokácia sa opakuje na každom lôžku, kľúč riadku je preto `riadok + id`). */
  id: string;
  type: BarType;
  /** `null` = celá izba (blokácia). */
  bedId: number | null;
  /** Skutočné dátumy (aj mimo okna). */
  from: string;
  to: string;
  /** Stĺpce okna, orezané; pásik začína v polovici dňa `startCol` a končí v polovici dňa `endCol`. */
  startCol: number;
  endCol: number;
  clippedStart: boolean;
  clippedEnd: boolean;
  label?: string;
  status?: string;
  locked?: boolean;
  flags?: string[];
  /** Poznámka karty označená na zobrazenie v pláne. */
  hasNote?: boolean;
  /** Počet viditeľných komentárov karty. */
  commentCount?: number;
  stayId?: number;
  reservationId?: number;
  partyId?: number;
  reason?: string;
}

export type PlanRow =
  | { kind: 'room'; roomId: number; label: string; building: string; category: string; receptionId?: number }
  | { kind: 'bed'; roomId: number; bedId: number; bedLabel: string; extra: boolean; bars: BarVM[] };

export interface PlanVM {
  from: string;
  to: string;
  days: string[];
  businessDates: Record<number, string>;
  rows: PlanRow[];
}

export const ZOOMS = ['narrow', 'normal', 'wide'] as const;
export type Zoom = (typeof ZOOMS)[number];
export const DAY_WIDTH: Record<Zoom, number> = { narrow: 24, normal: 36, wide: 56 };

export interface PlanFilters {
  receptionId?: number;
  /** Prázdne = všetky budovy. */
  buildingIds: number[];
  /** Zobrazený rozsah dní (vrátane oboch koncov). */
  from: string;
  to: string;
  /** Izba bez blokácie s aspoň jedným riadnym lôžkom voľným v celom rozsahu (ako na serveri). */
  freeOnly: boolean;
  /** Izba bez blokácie a bez akéhokoľvek pobytu v rozsahu. */
  fullyFree: boolean;
  /** Izba s aspoň jedným pobytom v rozsahu (blokácia sa nepočíta). */
  occupiedOnly?: boolean;
}

export interface PlanPreferences {
  /** `plan.showExtraBeds`: `false` skryje prázdne prístelky. */
  showExtraBeds?: boolean;
}

/** Posledný pohľad (`plan.defaultView`); dni sú relatívne k obchodnému dňu. */
export interface DefaultView {
  receptionId?: number;
  buildingIds: number[];
  daysBack: number;
  daysAhead: number;
  freeOnly: boolean;
  fullyFree: boolean;
  occupiedOnly: boolean;
  zoom: Zoom;
}

export const DEFAULT_VIEW: DefaultView = {
  buildingIds: [],
  daysBack: 1,
  daysAhead: 13,
  freeOnly: false,
  fullyFree: false,
  occupiedOnly: false,
  zoom: 'normal',
};
