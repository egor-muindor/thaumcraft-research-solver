import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { isValidLink, primalVec, mult, neighbors } from '../../app/src/core/aspectGraph';

const data = buildAspectData();

describe('isValidLink', () => {
  it('true for directly-combined aspects', () => {
    expect(isValidLink(data, 'magic', 'void')).toBe(true);
    expect(isValidLink(data, 'void', 'magic')).toBe(true);
  });
  it('false for identical aspects (no self-link)', () => {
    expect(isValidLink(data, 'air', 'air')).toBe(false);
  });
  it('false for siblings / unrelated aspects', () => {
    expect(isValidLink(data, 'light', 'energy')).toBe(false);
    expect(isValidLink(data, 'air', 'earth')).toBe(false);
  });
});

describe('neighbors', () => {
  it('returns the adjacency set', () => {
    expect(neighbors(data, 'magic').has('energy')).toBe(true);
  });
});

describe('primalVec', () => {
  it('maps a primal to itself with count 1', () => {
    expect([...primalVec(data, 'air')]).toEqual([['air', 1]]);
  });
  it('decomposes a compound into a primal multiset', () => {
    // void = air + entropy
    const v = primalVec(data, 'void');
    expect(v.get('air')).toBe(1);
    expect(v.get('entropy')).toBe(1);
    // magic = void + energy = (air+entropy) + (order+fire)
    const m = primalVec(data, 'magic');
    expect(m.get('air')).toBe(1);
    expect(m.get('entropy')).toBe(1);
    expect(m.get('order')).toBe(1);
    expect(m.get('fire')).toBe(1);
  });
  it('only contains primals as keys', () => {
    for (const k of primalVec(data, 'electricity').keys()) {
      expect(data.primals.has(k)).toBe(true);
    }
  });
});

describe('aspectGraph encapsulation (defensive copies)', () => {
  const d = buildAspectData();
  it('neighbors() result cannot corrupt the backing graph', () => {
    const ns = neighbors(d, 'air') as unknown as Set<string>;
    ns.add('earth');
    expect(isValidLink(d, 'air', 'earth')).toBe(false);
    expect(neighbors(d, 'air').has('earth')).toBe(false);
  });
  it('primalVec() result cannot poison the memo cache', () => {
    const v = primalVec(d, 'void') as unknown as Map<string, number>;
    v.set('air', 999);
    expect(primalVec(d, 'void').get('air')).toBe(1);
  });
});

describe('mult (direct multiplicity in a recipe)', () => {
  it('is 0 for a primal target', () => {
    expect(mult(data, 'air', 'air')).toBe(0);
  });
  it('is 1 for each distinct component', () => {
    expect(mult(data, 'void', 'magic')).toBe(1);
    expect(mult(data, 'energy', 'magic')).toBe(1);
    expect(mult(data, 'air', 'magic')).toBe(0);
  });
  it('counts repeats (synthetic X = air + air => 2)', () => {
    const d2 = buildAspectData({ overrideCombinations: { dbl: ['air', 'air'] }, addons: [], overrideTranslate: { dbl: 'dbl' } });
    expect(mult(d2, 'air', 'dbl')).toBe(2);
  });
});
