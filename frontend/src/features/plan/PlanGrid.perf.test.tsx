import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../shared/test/render';
import { buildPlanVM } from './buildPlanVM';
import { resolveColors } from './colors';
import { PlanGrid } from './PlanGrid';
import { generatePlan } from './testData';

/**
 * Výkon (DLD kap. 9.2): 200 izieb × 31 dní, prvé vykreslenie do 500 ms na vývojovom PC.
 * V CI (jsdom, zdieľaný runner) je limit voľnejší; skutočné meranie je v popise PR.
 */
describe('PlanGrid – výkon', () => {
  it('200 izieb × 31 dní: zostavenie modelu a prvé vykreslenie', () => {
    const plan = generatePlan(200, 31);
    const filters = { receptionId: 2, buildingIds: [], from: '2026-11-17', to: '2026-12-17', freeOnly: false, fullyFree: false };

    const t0 = performance.now();
    const vm = buildPlanVM(plan, filters);
    const t1 = performance.now();
    renderWithProviders(
      <PlanGrid vm={vm} zoom="normal" colors={resolveColors(null)} dataFrom="2026-11-17" dataTo="2026-12-17" receptionId={2} onSelectBar={() => {}} />,
    );
    const t2 = performance.now();

    expect(vm.days).toHaveLength(31);
    expect(vm.rows).toHaveLength(200 * 4); // izba + 2 lôžka + prístelka
    // virtualizácia: v DOM je len viditeľný výsek, nie všetkých 800 riadkov
    const rendered = screen.getAllByTestId(/plan-(room|bed)-row/).length;
    expect(rendered).toBeLessThan(60);
    console.info(`[perf] buildPlanVM ${(t1 - t0).toFixed(1)} ms, vykreslenie ${(t2 - t1).toFixed(1)} ms, riadkov v DOM ${rendered}/${vm.rows.length}`);
    expect(t2 - t0).toBeLessThan(2000);
  });

  it('200 izieb: so zapnutým ťahaním sa vykreslí len viditeľný výsek a pásiky sú ťahateľné', () => {
    const vm = buildPlanVM(generatePlan(200, 31), { receptionId: 2, buildingIds: [], from: '2026-11-17', to: '2026-12-17', freeOnly: false, fullyFree: false });
    const dnd = { perms: { move: true, moveLocked: false, backdate: true }, drag: null, occupied: [], onBarMenu: () => {} };
    const t0 = performance.now();
    renderWithProviders(
      <PlanGrid vm={vm} zoom="normal" colors={resolveColors(null)} dataFrom="2026-11-17" dataTo="2026-12-17" receptionId={2} onSelectBar={() => {}} dnd={dnd} />,
    );
    const ms = performance.now() - t0;
    expect(screen.getAllByTestId(/plan-(room|bed)-row/).length).toBeLessThan(60);
    expect(screen.getAllByTestId('resize-handle').length).toBeGreaterThan(0);
    console.info(`[perf] vykreslenie s ťahaním ${ms.toFixed(1)} ms`);
    expect(ms).toBeLessThan(2000);
  });
});
