export interface SteinerGraph {
  readonly size: number;
  neighbors(v: number): readonly number[];
  weight(v: number): number; // node weight (>= 0), may be +Infinity for forbidden nodes
  readonly terminals: readonly number[]; // distinct node ids
}

export const MAX_STEINER_TERMINALS = 16;

/**
 * Exact minimum node-weighted Steiner tree weight spanning all terminals.
 * O(3^k n + 2^k n^2), k = terminals. Returns +Infinity if terminals can't be connected.
 */
export function steinerNodeWeighted(g: SteinerGraph): number {
  const k = g.terminals.length;
  const n = g.size;
  if (k === 0) return 0;
  if (k === 1) return g.weight(g.terminals[0]!);
  if (k > MAX_STEINER_TERMINALS) {
    throw new Error(`steinerNodeWeighted: too many terminals (${k}); exponential DP supports at most ${MAX_STEINER_TERMINALS}`);
  }

  const full = (1 << k) - 1;
  const INF = Number.POSITIVE_INFINITY;
  // dp[mask*n + v]
  const dp = new Float64Array((1 << k) * n).fill(INF);

  // base: single terminal masks
  for (let i = 0; i < k; i++) {
    const t = g.terminals[i]!;
    dp[(1 << i) * n + t] = g.weight(t);
  }

  for (let mask = 1; mask <= full; mask++) {
    const base = mask * n;
    // (a) merge: dp[mask][v] = min over submask s of dp[s][v] + dp[mask\s][v] - weight(v)
    if ((mask & (mask - 1)) !== 0) { // mask has >=2 bits
      for (let v = 0; v < n; v++) {
        const wv = g.weight(v);
        if (!Number.isFinite(wv)) continue;
        let bestv = dp[base + v]!;
        for (let s = (mask - 1) & mask; s > 0; s = (s - 1) & mask) {
          const other = mask ^ s;
          if (s < other) break; // avoid double work; require s > other
          const a = dp[s * n + v]!;
          const b = dp[other * n + v]!;
          if (a !== INF && b !== INF) {
            const cand = a + b - wv;
            if (cand < bestv) bestv = cand;
          }
        }
        dp[base + v] = bestv;
      }
    }
    // (b) grow: Dijkstra relaxation over node weights within this mask layer
    dijkstraLayer(g, dp, base, n);
  }

  let best = INF;
  for (let v = 0; v < n; v++) if (dp[full * n + v]! < best) best = dp[full * n + v]!;
  return best;
}

function dijkstraLayer(g: SteinerGraph, dp: Float64Array, base: number, n: number): void {
  // Simple O(n^2) Dijkstra: extend tree to neighbor u paying weight(u).
  const visited = new Uint8Array(n);
  for (let iter = 0; iter < n; iter++) {
    let u = -1;
    let bu = Number.POSITIVE_INFINITY;
    for (let v = 0; v < n; v++) {
      if (!visited[v] && dp[base + v]! < bu) { bu = dp[base + v]!; u = v; }
    }
    if (u === -1) break;
    visited[u] = 1;
    for (const w of g.neighbors(u)) {
      const ww = g.weight(w);
      if (!Number.isFinite(ww)) continue;
      const cand = bu + ww;
      if (cand < dp[base + w]!) dp[base + w] = cand;
    }
  }
}
