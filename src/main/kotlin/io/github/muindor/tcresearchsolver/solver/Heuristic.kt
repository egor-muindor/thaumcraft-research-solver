package io.github.muindor.tcresearchsolver.solver

/**
 * Port of core/heuristic.ts
 *
 * Admissible lower-bound heuristic for the branch-and-bound solver.
 * Uses a node-weighted Steiner tree on the hex grid to estimate:
 *   - scarcity: minimum aspect-ink cost for free cells still needed
 *   - cells: minimum number of free cells still needed (or +Infinity if impossible)
 *
 * Gotchas applied: G2 (LinkedHashMap/LinkedHashSet), G4 (Double.POSITIVE_INFINITY / isFinite()),
 * G5 (stable sort), G7 (plain HashMap caches).
 */

// ---------------------------------------------------------------------------
// CellGraph — internal graph of non-DEAD board cells
// ---------------------------------------------------------------------------

private class CellGraph(
    val ids: LinkedHashMap<String, Int>,  // hexKey -> node id (non-DEAD only)
    val hexes: List<Hex>,
    val adj: Array<List<Int>>,
    val filledKeys: LinkedHashSet<String>, // anchors + placed (weight 0)
    val terminals: List<Int>,              // representative node id per anchor-bearing component
)

/**
 * Build a graph of all non-DEAD cells and compute anchor-component representatives.
 *
 * Only one terminal per anchor-bearing connected-filled-component is emitted,
 * so the Steiner DP measures cost to span anchor components (not individual cells).
 */
private fun buildCellGraph(board: Board): CellGraph {
    val ids = LinkedHashMap<String, Int>()
    val hexes = ArrayList<Hex>()
    for (h in boardCells(board.radius)) {
        val s = board.cells[hexKey(h)]
        if (s is CellState.Dead) continue // DEAD excluded
        ids[hexKey(h)] = hexes.size
        hexes.add(h)
    }

    val adj = Array<ArrayList<Int>>(hexes.size) { ArrayList() }
    for (i in 0 until hexes.size) {
        for (n in neighborsOf(hexes[i])) {
            if (!isOnBoard(n, board.radius)) continue
            val j = ids[hexKey(n)]
            if (j != null) adj[i].add(j)
        }
    }

    val filled = filledCells(board)
    val filledKeys = LinkedHashSet(filled.map { hexKey(it.hex) })

    // anchor-components: BFS over filled adjacency, keep one representative id per component
    // that has >=1 anchor
    val anchorKeys = LinkedHashSet(anchorCells(board).map { hexKey(it.first) })
    val compOf = LinkedHashMap<String, Int>()
    var comp = 0
    for (c in filled) {
        val k = hexKey(c.hex)
        if (compOf.containsKey(k)) continue
        val stack = ArrayDeque<Hex>()
        stack.add(c.hex)
        compOf[k] = comp
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (n in neighborsOf(cur)) {
                val nk = hexKey(n)
                if (filledKeys.contains(nk) && !compOf.containsKey(nk)) {
                    compOf[nk] = comp
                    stack.add(n)
                }
            }
        }
        comp++
    }

    val compHasAnchor = BooleanArray(comp)
    for (k in anchorKeys) {
        val ci = compOf[k]
        if (ci != null) compHasAnchor[ci] = true
    }

    val repByComp = LinkedHashMap<Int, Int>()
    for ((k, ci) in compOf) {
        if (compHasAnchor[ci] && !repByComp.containsKey(ci)) {
            val nodeId = ids[k]
            if (nodeId != null) repByComp[ci] = nodeId
        }
    }
    val terminals = repByComp.values.toList()

    @Suppress("UNCHECKED_CAST")
    return CellGraph(ids, hexes, adj as Array<List<Int>>, filledKeys, terminals)
}

// ---------------------------------------------------------------------------
// steinerWith — run Steiner DP with a given weight for free cells
// ---------------------------------------------------------------------------

/**
 * Run [steinerNodeWeighted] on the cell graph.
 * Filled cells (anchor + placed) have weight 0; free cells have [freeWeight].
 */
private fun steinerWith(graph: CellGraph, freeWeight: Double): Double {
    val g = object : SteinerGraph {
        override val size: Int = graph.hexes.size
        override fun neighbors(v: Int): List<Int> = graph.adj[v]
        override fun weight(v: Int): Double =
            if (graph.filledKeys.contains(hexKey(graph.hexes[v]))) 0.0 else freeWeight
        override val terminals: List<Int> = graph.terminals
    }
    return steinerNodeWeighted(g)
}

// ---------------------------------------------------------------------------
// remainderHeuristic — public API
// ---------------------------------------------------------------------------

/**
 * Admissible lower-bound heuristic for the branch-and-bound solver (spec §5.2).
 *
 * Returns a [Cost] whose:
 *   - `scarcity` = minimum scarcity cost of free cells needed to span all anchor components
 *   - `cells`    = minimum number of such free cells (or +Infinity if disconnected)
 *
 * If ≤1 anchor-bearing component exists, returns (0, 0) immediately (no spanning needed).
 */
fun remainderHeuristic(data: AspectData, board: Board, inv: Inventory): Cost {
    val graph = buildCellGraph(board)
    if (graph.terminals.size <= 1) return Cost(0.0, 0.0)

    val w = globalMinObtain(inv, data) // admissible per-free-cell weight (>=0, may be 0)
    val totalScarcity = steinerWith(graph, w)
    val totalCells = steinerWith(graph, 1.0)

    // Filled cells have weight 0, so the tree weight IS the remainder cost for free cells only.
    val hScarcity = totalScarcity
    val hCells = if (totalCells.isFinite()) totalCells else Double.POSITIVE_INFINITY
    return Cost(hScarcity, hCells)
}
