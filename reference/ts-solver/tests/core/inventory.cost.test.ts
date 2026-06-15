import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import {
  DEFAULT_THRESHOLD, BASE, K, makeInventory, validateInventory,
  directPenalty, obtainCost, globalMinObtain,
} from '../../app/src/core/inventory';

const data = buildAspectData();

describe('validateInventory', () => {
  it('accepts non-negative integer supply and threshold>0', () => {
    expect(() => validateInventory(makeInventory([['air', 10]], 50))).not.toThrow();
  });
  it('rejects negative supply', () => {
    expect(() => validateInventory(makeInventory([['air', -1]], 50))).toThrow(/air/);
  });
  it('rejects non-integer supply', () => {
    expect(() => validateInventory(makeInventory([['air', 1.5]], 50))).toThrow(/air/);
  });
  it('rejects threshold <= 0', () => {
    expect(() => validateInventory(makeInventory([], 0))).toThrow(/threshold/);
  });
});

describe('directPenalty (spec §4.2)', () => {
  const inv = makeInventory([['air', 50], ['fire', 10], ['water', 0]], DEFAULT_THRESHOLD);
  it('is 0 for abundant (supply >= threshold)', () => {
    expect(directPenalty(inv, data, 'air')).toBe(0);
  });
  it('is base + k*(threshold-supply) for scarce', () => {
    expect(directPenalty(inv, data, 'fire')).toBe(BASE + K * (DEFAULT_THRESHOLD - 10));
  });
  it('is +Infinity for zero supply (must craft)', () => {
    expect(directPenalty(inv, data, 'water')).toBe(Number.POSITIVE_INFINITY);
  });
});

describe('obtainCost (spec §4.2)', () => {
  it('equals directPenalty for a primal', () => {
    const inv = makeInventory([['air', 10]], DEFAULT_THRESHOLD);
    expect(obtainCost(inv, data, 'air')).toBe(directPenalty(inv, data, 'air'));
  });

  it('lets an abundant component rescue a zero-supply compound via crafting', () => {
    // void = air + entropy. supply[void]=0 (direct +Inf) but air & entropy abundant => craft cost finite.
    const inv = makeInventory([['air', 100], ['entropy', 100]], DEFAULT_THRESHOLD);
    expect(directPenalty(inv, data, 'void')).toBe(Number.POSITIVE_INFINITY);
    expect(obtainCost(inv, data, 'void')).toBe(0); // 0 + 0 from abundant primals
  });

  it('prefers direct when cheaper than crafting', () => {
    // void abundant directly => obtainCost 0 even if components scarce
    const inv = makeInventory([['void', 100], ['air', 1], ['entropy', 1]], DEFAULT_THRESHOLD);
    expect(obtainCost(inv, data, 'void')).toBe(0);
  });

  it('is monotone: more supply never increases obtainCost', () => {
    const lean = makeInventory([['air', 5], ['entropy', 5]], DEFAULT_THRESHOLD);
    const rich = makeInventory([['air', 80], ['entropy', 80]], DEFAULT_THRESHOLD);
    expect(obtainCost(rich, data, 'void')).toBeLessThanOrEqual(obtainCost(lean, data, 'void'));
  });
});

describe('globalMinObtain', () => {
  it('is the min obtainCost across the universe (0 when any aspect is abundant)', () => {
    const inv = makeInventory([['air', 100]], DEFAULT_THRESHOLD);
    expect(globalMinObtain(inv, data)).toBe(0);
  });
});

describe('obtainCost cache soundness', () => {
  it('keys the obtainCost cache on AspectData too (no stale cost across data on one Inventory)', () => {
    const inv = makeInventory([['air', 0], ['fire', 100], ['water', 100]], DEFAULT_THRESHOLD);
    const dataA = buildAspectData({ overrideCombinations: { compound: ['fire', 'water'] }, addons: [], overrideTranslate: { compound: 'compound' } });
    const dataB = buildAspectData({ overrideCombinations: { compound: ['air', 'fire'] }, addons: [], overrideTranslate: { compound: 'compound' } });
    expect(obtainCost(inv, dataA, 'compound')).toBe(0);
    expect(obtainCost(inv, dataB, 'compound')).toBe(Number.POSITIVE_INFINITY);
  });
});
