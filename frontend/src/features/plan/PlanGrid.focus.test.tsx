import { fireEvent, screen, waitFor } from '@testing-library/react';
import { renderWithProviders } from '../../shared/test/render';
import { buildPlanVM } from './buildPlanVM';
import { resolveColors } from './colors';
import { PlanGrid } from './PlanGrid';
import { generatePlan } from './testData';

const filters = { receptionId: 2, buildingIds: [], from: '2026-11-17', to: '2026-12-17', freeOnly: false, fullyFree: false };

describe('PlanGrid – odkaz na izbu (?roomId)', () => {
  const original = Element.prototype.scrollTo;
  const sizes = ['scrollHeight', 'clientHeight'] as const;
  const descriptors = sizes.map((k) => Object.getOwnPropertyDescriptor(HTMLElement.prototype, k));
  beforeEach(() => {
    // jsdom nemá rozmery: mriežka je vysoká 800 px a jej obsah celý zoznam riadkov
    const isGrid = (el: HTMLElement) => el.dataset.testid === 'plan-grid';
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', { configurable: true, get(this: HTMLElement) { return isGrid(this) ? 100_000 : 0; } });
    Object.defineProperty(HTMLElement.prototype, 'clientHeight', { configurable: true, get(this: HTMLElement) { return isGrid(this) ? 800 : 0; } });
    // jsdom nevie rolovať: posun nastaví scrollTop a ohlási udalosť scroll ako prehliadač
    Element.prototype.scrollTo = function (this: Element, opts?: ScrollToOptions | number) {
      if (typeof opts === 'object' && opts.top !== undefined) this.scrollTop = opts.top;
      fireEvent.scroll(this);
    } as typeof Element.prototype.scrollTo;
  });
  afterEach(() => {
    Element.prototype.scrollTo = original;
    sizes.forEach((k, i) => {
      const d = descriptors[i];
      if (d) Object.defineProperty(HTMLElement.prototype, k, d);
      else delete (HTMLElement.prototype as unknown as Record<string, unknown>)[k];
    });
  });

  it('odroluje aj na izbu mimo vykresleného výseku a zvýrazní ju', async () => {
    const vm = buildPlanVM(generatePlan(200, 31), filters);
    renderWithProviders(
      <PlanGrid vm={vm} zoom="normal" colors={resolveColors(null)} dataFrom="2026-11-17" dataTo="2026-12-17" receptionId={2} onSelectBar={() => {}} focusRoomId={190} />,
    );
    // izba 190 je riadok 756 z 800; bez rolovania by v DOM nebola
    const row = await waitFor(() => {
      const el = document.querySelector('[data-testid="plan-room-row"][data-room-id="190"]');
      expect(el).not.toBeNull();
      return el as HTMLElement;
    });
    expect(row).toHaveAttribute('data-focused', 'true');
    expect(screen.getByTestId('plan-grid').scrollTop).toBeGreaterThan(0);
    expect(document.querySelector('[data-room-id="1"]')).toBeNull();
  });

  it('neznáma izba (napr. iná recepcia alebo filter budov) nič neposunie', () => {
    const vm = buildPlanVM(generatePlan(20, 31), filters);
    renderWithProviders(
      <PlanGrid vm={vm} zoom="normal" colors={resolveColors(null)} dataFrom="2026-11-17" dataTo="2026-12-17" receptionId={2} onSelectBar={() => {}} focusRoomId={999} />,
    );
    expect(screen.getByTestId('plan-grid').scrollTop).toBe(0);
    expect(document.querySelector('[data-focused]')).toBeNull();
  });
});
