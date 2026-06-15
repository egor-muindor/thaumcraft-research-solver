import { describe, expect, it } from 'bun:test';
import { steinerNodeWeighted, type SteinerGraph } from '../../app/src/core/steiner';

// Build a small explicit graph: a path 0-1-2-3 ; weights all 1 except terminals weight 0.
function pathGraph(n: number, termWeights: number, freeWeight: number): SteinerGraph {
  const adj: number[][] = Array.from({ length: n }, () => []);
  for (let i = 0; i + 1 < n; i++) { adj[i]!.push(i + 1); adj[i + 1]!.push(i); }
  const weight = (v: number) => (v === 0 || v === n - 1 ? termWeights : freeWeight);
  return { size: n, neighbors: (v) => adj[v]!, weight, terminals: [0, n - 1] };
}

describe('Dreyfus–Wagner node-weighted Steiner', () => {
  it('single terminal => its own weight', () => {
    const g: SteinerGraph = { size: 1, neighbors: () => [], weight: () => 0, terminals: [0] };
    expect(steinerNodeWeighted(g)).toBe(0);
  });

  it('path of 4: 2 terminals (weight 0) + 2 inner free (weight 1) => 2', () => {
    expect(steinerNodeWeighted(pathGraph(4, 0, 1))).toBe(2);
  });

  it('counts terminal weights too', () => {
    // terminals weight 5 each, 2 inner weight 1 => 12
    expect(steinerNodeWeighted(pathGraph(4, 5, 1))).toBe(12);
  });

  it('picks the cheaper of two parallel routes (star, not MST overcount)', () => {
    // center 0 connects to t1=1, t2=2, t3=3 (all terminals). Optimal Steiner = center+3 terminals.
    // weights: center 1, terminals 0 => total 1 (NOT 2 like a pairwise-MST overestimate).
    const adj: number[][] = [[1, 2, 3], [0], [0], [0]];
    const g: SteinerGraph = { size: 4, neighbors: (v) => adj[v]!, weight: (v) => (v === 0 ? 1 : 0), terminals: [1, 2, 3] };
    expect(steinerNodeWeighted(g)).toBe(1);
  });

  it('returns Infinity when terminals are disconnected', () => {
    const adj: number[][] = [[], []];
    const g: SteinerGraph = { size: 2, neighbors: (v) => adj[v]!, weight: () => 0, terminals: [0, 1] };
    expect(steinerNodeWeighted(g)).toBe(Number.POSITIVE_INFINITY);
  });

  it('throws a clear error (not a cryptic RangeError) when terminal count exceeds the limit', () => {
    const n = 31;
    const adj: number[][] = Array.from({ length: n }, () => []);
    const g: SteinerGraph = { size: n, neighbors: (v) => adj[v]!, weight: () => 0, terminals: Array.from({ length: 31 }, (_, i) => i) };
    expect(() => steinerNodeWeighted(g)).toThrow(/too many terminals/);
  });
});
