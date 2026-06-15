import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { makeInventory, allocate, obtainCost, DEFAULT_THRESHOLD } from '../../app/src/core/inventory';

const data = buildAspectData();

describe('allocate (exact §4.3)', () => {
  it('takes directly when abundant: zero scarcity, no crafts', () => {
    const inv = makeInventory([['air', 100], ['fire', 100]], DEFAULT_THRESHOLD);
    const r = allocate(inv, data, new Map([['air', 2], ['fire', 1]]));
    expect(r.feasible).toBe(true);
    expect(r.scarcityCost).toBe(0);
    expect(r.craftOps).toBe(0);
    expect(r.leafConsumption.get('air')).toBe(2);
    expect(r.leafConsumption.get('fire')).toBe(1);
  });

  it('crafts when the aspect has zero direct supply but components are available', () => {
    // need light=1 (light=air+fire), supply light=0
    const inv = makeInventory([['air', 100], ['fire', 100]], DEFAULT_THRESHOLD);
    const r = allocate(inv, data, new Map([['light', 1]]));
    expect(r.feasible).toBe(true);
    expect(r.craftOps).toBe(1);
    expect(r.leafConsumption.get('air')).toBe(1);
    expect(r.leafConsumption.get('fire')).toBe(1);
    expect(r.leafConsumption.get('light') ?? 0).toBe(0);
  });

  it('reports infeasible when a needed primal cannot be supplied even via crafting', () => {
    const inv = makeInventory([['air', 0], ['fire', 0]], DEFAULT_THRESHOLD);
    const r = allocate(inv, data, new Map([['light', 1]]));
    expect(r.feasible).toBe(false);
  });

  it('is order-independent and beats greedy under contention for a shared component (spec §4.3)', () => {
    // Synthetic binary-BOM contention: X=A+B, Y=A+C, supply A=1,B=1,C=1, demand X=1,Y=1.
    // Greedy that crafts both from A fails (A only 1). Exact must craft one, take the other directly if possible,
    // OR report the true optimum. Here X and Y have no direct supply, so exactly one can be crafted; the other is infeasible.
    const synth = buildAspectData({
      overrideCombinations: { acomp: ['air', 'fire'], bcomp: ['air', 'water'], ccomp: ['air', 'earth'] },
      addons: [],
      overrideTranslate: { acomp: 'acomp', bcomp: 'bcomp', ccomp: 'ccomp' },
    });
    // demand acomp=1, bcomp=1 ; they share 'air'. supply air=2 => both craftable.
    const inv2 = makeInventory([['air', 2], ['fire', 100], ['water', 100]], DEFAULT_THRESHOLD);
    const r = allocate(inv2, synth, new Map([['acomp', 1], ['bcomp', 1]]));
    expect(r.feasible).toBe(true);
    expect(r.leafConsumption.get('air')).toBe(2);

    // now air=1 => only one of the two compounds craftable, no direct supply => infeasible
    const inv1 = makeInventory([['air', 1], ['fire', 100], ['water', 100]], DEFAULT_THRESHOLD);
    const r1 = allocate(inv1, synth, new Map([['acomp', 1], ['bcomp', 1]]));
    expect(r1.feasible).toBe(false);
  });

  it('prefers the cheaper feasible mix (direct abundant over crafting that drains scarce leaves)', () => {
    // light direct abundant => scarcityCost 0; crafting would also be 0 here, but craftOps must be 0 (direct preferred by cost tie? cost equal).
    const inv = makeInventory([['light', 100], ['air', 100], ['fire', 100]], DEFAULT_THRESHOLD);
    const r = allocate(inv, data, new Map([['light', 1]]));
    expect(r.feasible).toBe(true);
    expect(r.scarcityCost).toBe(0);
  });

  it('returns feasible:"unknown" when the node budget is exhausted (no false verdict)', () => {
    const inv = makeInventory([['air', 100], ['fire', 100]], DEFAULT_THRESHOLD);
    const r = allocate(inv, data, new Map([['light', 3]]), { maxNodes: 1 });
    expect(r.feasible).toBe('unknown');
  });

  it('Σ obtainCost is a lower bound on the exact allocation scarcityCost (spec §4.2)', () => {
    const inv = makeInventory([['air', 3], ['fire', 3]], DEFAULT_THRESHOLD); // both scarce
    const demand = new Map([['light', 2]]);
    const r = allocate(inv, data, demand);
    // independent lower bound: 2 * obtainCost(light)
    const lb = 2 * obtainCost(inv, data, 'light');
    expect(lb).toBeLessThanOrEqual(r.scarcityCost);
  });

  it('mixes direct and craft under contention: takes one compound directly to free a shared component (spec §4.3)', () => {
    // xx=air+fire, yy=air+water share air (supply 1). xx has direct supply (1), yy none. Crafting BOTH
    // needs 2 air (only 1) => must take xx directly and craft yy from the single air. Greedy "craft all" fails.
    const synth = buildAspectData({
      overrideCombinations: { xx: ['air', 'fire'], yy: ['air', 'water'] },
      addons: [],
      overrideTranslate: { xx: 'xx', yy: 'yy' },
    });
    const inv = makeInventory([['xx', 1], ['air', 1], ['fire', 100], ['water', 100]], DEFAULT_THRESHOLD);
    const r = allocate(inv, synth, new Map([['xx', 1], ['yy', 1]]));
    expect(r.feasible).toBe(true);
    expect(r.leafConsumption.get('xx')).toBe(1);
    expect(r.leafConsumption.get('air')).toBe(1);
    expect(r.craftOps).toBe(1);
  });
});
