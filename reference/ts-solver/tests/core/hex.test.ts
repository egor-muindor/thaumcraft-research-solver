import { describe, expect, it } from 'bun:test';
import { hexKey, parseHexKey, neighborsOf, distance, boardCells, isOnBoard, HEX_DIRECTIONS } from '../../app/src/core/hex';

describe('hex geometry', () => {
  it('round-trips a coord through hexKey/parseHexKey', () => {
    expect(parseHexKey(hexKey({ q: -2, r: 3 }))).toEqual({ q: -2, r: 3 });
    expect(hexKey({ q: 0, r: 0 })).toBe('0,0');
  });

  it('has 6 unit directions summing to zero', () => {
    expect(HEX_DIRECTIONS).toHaveLength(6);
    const sum = HEX_DIRECTIONS.reduce((a, d) => ({ q: a.q + d.q, r: a.r + d.r }), { q: 0, r: 0 });
    expect(sum).toEqual({ q: 0, r: 0 });
  });

  it('returns 6 neighbors each at distance 1', () => {
    const n = neighborsOf({ q: 0, r: 0 });
    expect(n).toHaveLength(6);
    for (const h of n) expect(distance({ q: 0, r: 0 }, h)).toBe(1);
    // all distinct
    expect(new Set(n.map(hexKey)).size).toBe(6);
  });

  it('computes cube distance', () => {
    expect(distance({ q: 0, r: 0 }, { q: 0, r: 0 })).toBe(0);
    expect(distance({ q: 0, r: 0 }, { q: 2, r: -1 })).toBe(2);
    expect(distance({ q: -1, r: -1 }, { q: 1, r: 1 })).toBe(4);
  });

  it('generates the right number of cells per radius (1+3R(R+1))', () => {
    expect(boardCells(2)).toHaveLength(19);
    expect(boardCells(3)).toHaveLength(37);
    expect(boardCells(4)).toHaveLength(61);
    expect(boardCells(5)).toHaveLength(91);
  });

  it('isOnBoard agrees with distance-from-center', () => {
    expect(isOnBoard({ q: 2, r: 0 }, 2)).toBe(true);
    expect(isOnBoard({ q: 3, r: 0 }, 2)).toBe(false);
    expect(boardCells(3).every((h) => isOnBoard(h, 3))).toBe(true);
  });

  it('rejects malformed hex keys (also guards board deserialization, spec §2.3)', () => {
    for (const bad of ['1', ',1', '1,', '1,2,3', '', 'a,b', '1.5,2', ' ,3']) {
      expect(() => parseHexKey(bad)).toThrow();
    }
  });
});
