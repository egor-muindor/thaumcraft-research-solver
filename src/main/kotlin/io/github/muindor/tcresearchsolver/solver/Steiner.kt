package io.github.muindor.tcresearchsolver.solver

interface SteinerGraph {
    val size: Int
    fun neighbors(v: Int): List<Int>
    fun weight(v: Int): Double  // node weight (>= 0), may be +Infinity for forbidden nodes
    val terminals: List<Int>    // distinct node ids
}

const val MAX_STEINER_TERMINALS = 16

/**
 * Exact minimum node-weighted Steiner tree weight spanning all terminals.
 * O(3^k * n + 2^k * n^2), k = terminals. Returns +Infinity if terminals can't be connected.
 * Port of core/steiner.ts (Dreyfus-Wagner algorithm).
 */
fun steinerNodeWeighted(g: SteinerGraph): Double {
    val k = g.terminals.size
    val n = g.size
    if (k == 0) return 0.0
    if (k == 1) return g.weight(g.terminals[0])
    if (k > MAX_STEINER_TERMINALS) {
        throw IllegalArgumentException(
            "steinerNodeWeighted: too many terminals ($k); exponential DP supports at most $MAX_STEINER_TERMINALS"
        )
    }

    val full = (1 shl k) - 1
    val INF = Double.POSITIVE_INFINITY
    // dp[mask * n + v]
    val dp = DoubleArray((1 shl k) * n) { INF }

    // base: single terminal masks
    for (i in 0 until k) {
        val t = g.terminals[i]
        dp[(1 shl i) * n + t] = g.weight(t)
    }

    for (mask in 1..full) {
        val base = mask * n
        // (a) merge: dp[mask][v] = min over submask s of dp[s][v] + dp[mask\s][v] - weight(v)
        if ((mask and (mask - 1)) != 0) { // mask has >= 2 bits
            for (v in 0 until n) {
                val wv = g.weight(v)
                if (!wv.isFinite()) continue
                var bestv = dp[base + v]
                var s = (mask - 1) and mask
                while (s > 0) {
                    val other = mask xor s
                    if (s < other) break // avoid double work; require s > other
                    val a = dp[s * n + v]
                    val b = dp[other * n + v]
                    if (a != INF && b != INF) {
                        val cand = a + b - wv
                        if (cand < bestv) bestv = cand
                    }
                    s = (s - 1) and mask
                }
                dp[base + v] = bestv
            }
        }
        // (b) grow: Dijkstra relaxation over node weights within this mask layer
        dijkstraLayer(g, dp, base, n)
    }

    var best = INF
    for (v in 0 until n) {
        val c = dp[full * n + v]
        if (c < best) best = c
    }
    return best
}

private fun dijkstraLayer(g: SteinerGraph, dp: DoubleArray, base: Int, n: Int) {
    // Simple O(n^2) Dijkstra: extend tree to neighbor u paying weight(u).
    val visited = BooleanArray(n)
    for (iter in 0 until n) {
        var u = -1
        var bu = Double.POSITIVE_INFINITY
        for (v in 0 until n) {
            if (!visited[v] && dp[base + v] < bu) { bu = dp[base + v]; u = v }
        }
        if (u == -1) break
        visited[u] = true
        for (w in g.neighbors(u)) {
            val ww = g.weight(w)
            if (!ww.isFinite()) continue
            val cand = bu + ww
            if (cand < dp[base + w]) dp[base + w] = cand
        }
    }
}
