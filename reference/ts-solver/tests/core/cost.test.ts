import { describe, expect, it } from 'bun:test';
import { type Cost, compareCost, addCost, ZERO_COST, INF_COST, lessThan } from '../../app/src/core/cost';

describe('lexicographic cost (scarcity, cells)', () => {
  it('orders by scarcity first, then cells', () => {
    expect(compareCost({ scarcity: 1, cells: 100 }, { scarcity: 2, cells: 0 })).toBeLessThan(0);
    expect(compareCost({ scarcity: 2, cells: 1 }, { scarcity: 2, cells: 3 })).toBeLessThan(0);
    expect(compareCost({ scarcity: 2, cells: 3 }, { scarcity: 2, cells: 3 })).toBe(0);
  });
  it('adds componentwise (Infinity-safe)', () => {
    expect(addCost({ scarcity: 1, cells: 2 }, { scarcity: 3, cells: 4 })).toEqual({ scarcity: 4, cells: 6 });
    expect(addCost(INF_COST, ZERO_COST).scarcity).toBe(Number.POSITIVE_INFINITY);
  });
  it('lessThan is strict', () => {
    expect(lessThan({ scarcity: 1, cells: 0 }, { scarcity: 1, cells: 1 })).toBe(true);
    expect(lessThan({ scarcity: 1, cells: 1 }, { scarcity: 1, cells: 1 })).toBe(false);
  });
});
