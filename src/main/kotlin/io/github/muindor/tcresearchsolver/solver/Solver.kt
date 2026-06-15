package io.github.muindor.tcresearchsolver.solver

/**
 * Port of core/solver.ts
 *
 * Branch-and-bound solver for the Thaumcraft research hex grid.
 * Objective: lexicographic (scarcity, cells) — fewer is better.
 *
 * Gotchas applied: G1-G11. In particular:
 *   G2: LinkedHashMap/LinkedHashSet used everywhere TS uses Map/Set.
 *   G3: Cost holds two Doubles.
 *   G4: Double.POSITIVE_INFINITY / isFinite().
 *   G5: infinity-safe sort (no subtraction).
 *   G6: hex key string compare (String < identical to JS for ASCII keys).
 *   G7: one obtainCost cache HashMap reused across the whole solve.
 *   G10: throw IllegalArgumentException/IllegalStateException.
 *   G11: absent map key => default via ?:.
 */

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

enum class SolverStatus {
    OPTIMAL, FEASIBLE_TIMEOUT, UNKNOWN_TIMEOUT,
    INFEASIBLE_INVENTORY, UNSAT_PROVEN, CANCELLED, INVALID_INPUT
}

const val MAX_ANCHORS = 8

class SolveBudget(val maxNodes: Int, val maxTimeMs: Long, val beam: Int? = null)

class Progress(val nodes: Int, val best: Cost?, val timeMs: Long, val status: String)

/**
 * Explicit per-radius budgets (spec §5.5). `beam` caps include-branch aspect fan-out on heavy
 * boards and forces a *_TIMEOUT status (never *_PROVEN).
 */
val DEFAULT_BUDGETS: Map<Int, SolveBudget> = mapOf(
    2 to SolveBudget(500_000, 5_000),
    3 to SolveBudget(2_000_000, 10_000),
    4 to SolveBudget(4_000_000, 20_000, beam = 12),
    5 to SolveBudget(6_000_000, 30_000, beam = 8),
)

fun budgetForRadius(radius: Int): SolveBudget = DEFAULT_BUDGETS[radius] ?: DEFAULT_BUDGETS.getValue(5)

class SolveOptions(
    val data: AspectData,
    val board: Board,          // initial anchors + locked (pre-validated by caller)
    val inventory: Inventory,
    val budget: SolveBudget,
    val allocBudget: AllocBudget = AllocBudget(),
    val seed: Boolean = false, // enable the optional anytime seed; default off
    val onProgress: ((Progress) -> Unit)? = null,
    val shouldCancel: (() -> Boolean)? = null,
    val now: () -> Long = { System.currentTimeMillis() },
)

class SolveResult(
    val status: SolverStatus,
    val board: Board? = null,
    val cost: Cost? = null,
    val allocation: AllocationResult? = null,
    val nodes: Int = 0,
    val timeMs: Long = 0,
    val errors: List<ValidationError>? = null,
    val message: String? = null,
)

// ---------------------------------------------------------------------------
// solveWithValidation
// ---------------------------------------------------------------------------

fun solveWithValidation(opts: SolveOptions): SolveResult {
    // Inventory pre-validation (spec §4.1): reject negative/non-integer supply or threshold<=0
    // up front instead of letting it surface as a worker error mid-search.
    try {
        validateInventory(opts.inventory)
    } catch (err: Exception) {
        return SolveResult(SolverStatus.INVALID_INPUT, message = err.message)
    }
    // Anchor cap (spec §5.1): core boundary enforces max 8 anchors.
    val anchorCount = anchorCells(opts.board).size
    if (anchorCount > MAX_ANCHORS) {
        return SolveResult(SolverStatus.INVALID_INPUT, message = "too many anchors: $anchorCount (max $MAX_ANCHORS)")
    }
    val v = validate(opts.data, opts.board)
    val unfixable = v.errors.filter { e -> e.type != ValidationErrorType.ANCHORS_DISCONNECTED }
    if (unfixable.isNotEmpty()) {
        return SolveResult(SolverStatus.INVALID_INPUT, errors = unfixable)
    }
    return solve(opts)
}

// ---------------------------------------------------------------------------
// solve
// ---------------------------------------------------------------------------

private data class Placement(val key: String, val aspect: Aspect)

fun solve(opts: SolveOptions): SolveResult {
    val data = opts.data
    val initial = opts.board
    val inventory = opts.inventory
    val budget = opts.budget
    val allocBudget = opts.allocBudget
    val now = opts.now
    val start = now()
    val anchors = anchorCells(initial) // List<Pair<Hex, Aspect>>

    // 0/1 anchor => trivially solved (spec §5.1)
    if (anchors.size <= 1) {
        return SolveResult(SolverStatus.OPTIMAL, cloneBoard(initial), ZERO_COST, nodes = 0, timeMs = 0)
    }

    // Unfixable initial invalidity: link errors among fixed (anchor/locked) cells cannot be repaired by
    // filling EMPTY cells, so no valid board exists. ANCHORS_DISCONNECTED is fixable (solver connects them) => ignored.
    val initialErrors = validate(data, initial).errors.filter { e -> e.type != ValidationErrorType.ANCHORS_DISCONNECTED }
    if (initialErrors.isNotEmpty()) {
        return SolveResult(SolverStatus.UNSAT_PROVEN, nodes = 0, timeMs = 0)
    }

    // Pre-compute anchor hex keys for fast connectivity check.
    val anchorKeys: List<String> = anchors.map { hexKey(it.first) }

    // working board mutated during DFS; placements stack of solver cells; excluded = frontier cells
    // decided to remain EMPTY in the current subtree (the include/exclude enumeration is non-redundant).
    val work = cloneBoard(initial)
    val placements = ArrayList<Placement>()
    val excluded = LinkedHashSet<String>()

    // One obtainCost cache map reused across the whole solve for speed (G7).
    val costCache = HashMap<Aspect, Double>()

    // Fast anchor connectivity check using work.cells directly (avoids boardCells scan).
    val fastAnchorsConnected = fun(): Boolean {
        val startKey = anchorKeys[0]
        val seen = LinkedHashSet<String>()
        seen.add(startKey)
        val queue = ArrayDeque<Hex>()
        queue.add(parseHexKey(startKey))
        while (queue.isNotEmpty()) {
            val cur = queue.removeLast()
            for (n in neighborsOf(cur)) {
                val nk = hexKey(n)
                if (seen.contains(nk)) continue
                val ns = work.cells[nk]
                if (ns == null || ns is CellState.Empty || ns is CellState.Dead) continue
                seen.add(nk)
                queue.add(n)
            }
        }
        for (ak in anchorKeys) if (!seen.contains(ak)) return false
        return true
    }

    data class Incumbent(val board: Board, val cost: Cost, val alloc: AllocationResult)

    var incumbent: Incumbent? = null
    var anyValidBoardFound = false
    var anyUnknownCompetitive = false
    var nodes = 0
    var cancelled = false
    var truncated = false // budget/beam hit before exhaustion

    val placedCost = fun(): Cost {
        var scarcity = 0.0
        for (p in placements) scarcity += obtainCost(inventory, data, p.aspect, costCache)
        return Cost(scarcity, placements.size.toDouble())
    }

    // the one frontier cell to decide next = lowest-hexKey EMPTY cell adjacent to the structure
    // that is not already excluded; null => every frontier cell decided (leaf).
    // Iterates work.cells directly (only non-EMPTY cells stored) to avoid scanning all board cells.
    val nextUndecidedFrontierCell = fun(): Hex? {
        var best: Hex? = null
        var bestK = ""
        val seen = LinkedHashSet<String>()
        for ((ck, cs) in work.cells) {
            if (cs is CellState.Dead) continue // DEAD cells have no placed aspect
            val ch = parseHexKey(ck)
            for (n in neighborsOf(ch)) {
                if (!isOnBoard(n, work.radius)) continue
                val nk = hexKey(n)
                if (!seen.add(nk)) continue
                if (excluded.contains(nk)) continue
                if ((work.cells[nk] ?: CellState.Empty) !is CellState.Empty) continue
                if (best == null || nk < bestK) { best = n; bestK = nk }
            }
        }
        return best
    }

    val reportProgress = fun() {
        opts.onProgress?.invoke(Progress(
            nodes,
            incumbent?.cost,
            now() - start,
            "searching",
        ))
    }

    val validPlacement = fun(h: Hex, a: Aspect): Boolean {
        for (n in neighborsOf(h)) {
            if (!isOnBoard(n, work.radius)) continue
            val ns = work.cells[hexKey(n)] ?: continue // EMPTY (absent from map)
            if (ns is CellState.Dead || ns is CellState.Empty) continue
            val na = when (ns) {
                is CellState.Anchor -> ns.aspect
                is CellState.Placed -> ns.aspect
                else -> continue
            }
            if (na == a) return false
            if (!isValidLink(data, na, a)) return false
        }
        return true
    }

    val onComplete = fun() {
        anyValidBoardFound = true // it's link-valid (placements were validated incrementally) and connected
        val gcost = placedCost()
        val alloc = allocate(inventory, data, demandOf(placements), allocBudget)
        if (alloc.feasible == Feasible.TRUE) {
            val cost = Cost(alloc.scarcityCost, placements.size.toDouble())
            if (incumbent == null || lessThan(cost, incumbent!!.cost)) {
                incumbent = Incumbent(cloneBoard(work), cost, alloc)
            }
        } else if (alloc.feasible == Feasible.UNKNOWN) {
            // Allocator budget exhausted for this board: feasibility unproven. Record that a competitive
            // unknown exists — this BLOCKS proof statuses (OPTIMAL/INFEASIBLE_INVENTORY degrade to *_TIMEOUT),
            // but DO NOT stop the search: continuing may still find a feasible incumbent (anytime, invariant 3).
            if (incumbent == null || lessThan(gcost, incumbent!!.cost)) {
                anyUnknownCompetitive = true
            }
        } // feasible == FALSE => discard entirely
    }

    // DFS with include/exclude branching (complete); periodic cancel/budget/progress checks.
    // Defined as a recursive local function to mirror the TS closure.
    fun dfs() {
        if (cancelled) return
        if (nodes >= budget.maxNodes) { truncated = true; return }
        if ((nodes and 1023) == 0) {
            if (opts.shouldCancel?.invoke() == true) { cancelled = true; return }
            if (now() - start > budget.maxTimeMs) { truncated = true; return }
            reportProgress()
        }
        nodes++

        // invariants 4 & 6: bound-prune ONLY once a finite feasible incumbent exists — and never on a
        // +∞ g_lb before then (such branches must reach a goal to set anyValidBoardFound => INFEASIBLE_INVENTORY).
        // Skip h computation when there is no incumbent (h is only needed for pruning).
        val inc = incumbent
        if (inc != null) {
            val g = placedCost()
            val h = remainderHeuristic(data, work, inventory)
            val f = addCost(g, h)
            if (compareCost(f, inc.cost) >= 0) return
        }

        if (fastAnchorsConnected()) onComplete() // invariant 3: keep searching past goals

        val cell = nextUndecidedFrontierCell() ?: return // every frontier cell decided => leaf

        // (a) INCLUDE: place each valid aspect (cheap obtainCost first). Infinity-safe compare:
        // `∞ - ∞ = NaN` would corrupt sort, so compare without subtraction (G5).
        val candidates = ArrayList<Aspect>()
        for (a in data.universe) if (validPlacement(cell, a)) candidates.add(a)
        candidates.sortWith { x, y ->
            val cx = obtainCost(inventory, data, x, costCache)
            val cy = obtainCost(inventory, data, y, costCache)
            if (cx == cy) 0 else if (cx < cy) -1 else 1
        }
        // Mirror JS truthiness: beam=0 is falsy in JS (same as no beam), so treat beam=0 as no-beam.
        val beam = budget.beam
        val limited: List<Aspect> = if (beam != null && beam > 0 && candidates.size > beam) {
            truncated = true
            candidates.subList(0, beam)
        } else {
            candidates
        }

        for (a in limited) {
            setState(work, cell, CellState.Placed(a, false))
            placements.add(Placement(hexKey(cell), a))
            dfs()
            placements.removeAt(placements.lastIndex)
            setState(work, cell, CellState.Empty)
            if (cancelled) return
        }

        // (b) EXCLUDE: leave `cell` permanently EMPTY in this subtree (enables frontier-skipping optima)
        excluded.add(hexKey(cell))
        dfs()
        excluded.remove(hexKey(cell))
    }

    // Anytime seeding (spec §5.3): a quick Dijkstra-stitched candidate validated before use.
    seedIncumbent(opts, costCache) { cand ->
        val v = validate(data, cand)
        if (v.valid && allAnchorsConnected(cand)) {
            val pls = solverPlacements(initial, cand)
            val alloc = allocate(inventory, data, demandOf(pls), allocBudget)
            if (alloc.feasible == Feasible.TRUE) {
                val cost = Cost(alloc.scarcityCost, pls.size.toDouble())
                if (incumbent == null || lessThan(cost, incumbent!!.cost)) {
                    incumbent = Incumbent(cloneBoard(cand), cost, alloc)
                }
            }
        }
    }

    dfs()

    val timeMs = now() - start
    val exhaustive = !truncated && !cancelled
    val inc = incumbent // capture after DFS

    if (cancelled) {
        return if (inc != null)
            SolveResult(SolverStatus.CANCELLED, inc.board, inc.cost, inc.alloc, nodes, timeMs)
        else
            SolveResult(SolverStatus.CANCELLED, nodes = nodes, timeMs = timeMs)
    }

    if (inc != null) {
        if (exhaustive && !anyUnknownCompetitive) {
            return SolveResult(SolverStatus.OPTIMAL, inc.board, inc.cost, inc.alloc, nodes, timeMs)
        }
        return SolveResult(SolverStatus.FEASIBLE_TIMEOUT, inc.board, inc.cost, inc.alloc, nodes, timeMs)
    }

    // no incumbent
    if (exhaustive) {
        if (!anyValidBoardFound) return SolveResult(SolverStatus.UNSAT_PROVEN, nodes = nodes, timeMs = timeMs)
        if (!anyUnknownCompetitive) return SolveResult(SolverStatus.INFEASIBLE_INVENTORY, nodes = nodes, timeMs = timeMs)
    }
    return SolveResult(SolverStatus.UNKNOWN_TIMEOUT, nodes = nodes, timeMs = timeMs)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun cloneBoard(b: Board): Board {
    val nb = createBoard(b.radius)
    for ((k, s) in b.cells) nb.cells[k] = s
    return nb
}

private fun demandOf(placements: List<Placement>): LinkedHashMap<Aspect, Int> {
    val d = LinkedHashMap<Aspect, Int>()
    for (p in placements) d[p.aspect] = (d[p.aspect] ?: 0) + 1
    return d
}

/** solver-placed cells = PLACED,locked=false present in `solved` that were EMPTY in `initial`. */
private fun solverPlacements(initial: Board, solved: Board): List<Placement> {
    val out = ArrayList<Placement>()
    for (h in boardCells(solved.radius)) {
        val s = getState(solved, h)
        val i = getState(initial, h)
        if (s is CellState.Placed && !s.locked && i is CellState.Empty)
            out.add(Placement(hexKey(h), s.aspect))
    }
    return out
}

// ---------------------------------------------------------------------------
// Anytime seed (spec §5.3)
// ---------------------------------------------------------------------------

/**
 * Anytime seed: pairwise-Dijkstra stitched candidate. UNTRUSTED — the accept gate runs
 * validate() + allAnchorsConnected + allocate before writing the incumbent, so a wrong
 * seed is silently dropped. Gated on opts.seed (default off) so unit tests of the pure
 * search are completely unaffected.
 */
private fun seedIncumbent(
    opts: SolveOptions,
    costCache: HashMap<Aspect, Double>,
    accept: (Board) -> Unit,
) {
    if (!opts.seed) return
    val data = opts.data
    val initial = opts.board
    val inventory = opts.inventory
    val anchors = anchorCells(initial)
    if (anchors.size < 2) return

    // Greedy chain: connect anchor[0] to each other anchor by the cheapest product-path,
    // laying placements onto a single candidate board so each later Dijkstra sees the
    // cells committed by earlier paths.
    val candidate = cloneBoard(initial)
    for (i in 1 until anchors.size) {
        val path = cheapestProductPath(data, candidate, inventory, anchors[0], anchors[i], costCache)
            ?: return // give up; exhaustive search still runs
        for ((hex, aspect) in path) {
            if (getState(candidate, hex) is CellState.Empty) {
                setState(candidate, hex, CellState.Placed(aspect, false))
            }
        }
    }
    accept(candidate) // trusted gate: validate + feasibility checks happen inside
}

private data class HexAspect(val hex: Hex, val aspect: Aspect)

/**
 * Dijkstra over a "product graph" whose states are (cell, aspect) pairs.
 * Returns the cheapest aspect-labelled path of cells from `from` to `to`
 * (excluding the endpoints themselves), or null if no path exists.
 *
 * Edge weight = obtainCost(newAspect). UNTRUSTED — caller validates.
 */
private fun cheapestProductPath(
    data: AspectData,
    board: Board,
    inv: Inventory,
    from: Pair<Hex, Aspect>,
    to: Pair<Hex, Aspect>,
    costCache: HashMap<Aspect, Double>,
): List<HexAspect>? {
    val fromHex = from.first; val fromAspect = from.second
    val toHex = to.first; val toAspect = to.second

    // Degenerate: if from and to are already adjacent and validly linked, no intermediate cells needed.
    for (n in neighborsOf(fromHex)) {
        if (n.q == toHex.q && n.r == toHex.r && isValidLink(data, fromAspect, toAspect)) {
            return emptyList()
        }
    }

    // State = "${hexKey}|${aspect}"
    val startState = "${hexKey(fromHex)}|$fromAspect"

    val dist = LinkedHashMap<String, Double>()
    dist[startState] = 0.0
    // prev maps state -> {prevState, hex, aspect, existing} for path reconstruction.
    // existing=true means the cell was already placed (traversal only, not a new placement).
    data class PrevEntry(val prevState: String, val hex: Hex, val aspect: Aspect, val existing: Boolean)
    val prev = HashMap<String, PrevEntry>()
    val visited = HashSet<String>()

    // Simple Dijkstra with linear scan for minimum (board radius <= 5, universe <= ~80 aspects,
    // so the priority queue is at most ~O(cells * aspects) ≈ 60*80 = 4800 entries — linear scan fine).
    while (true) {
        // Find the unvisited state with minimum distance
        var minCost = Double.POSITIVE_INFINITY
        var minState: String? = null
        for ((s, d) in dist) {
            if (!visited.contains(s) && d < minCost) {
                minCost = d
                minState = s
            }
        }
        if (minState == null) return null // queue empty, no path

        visited.add(minState)
        val pipeIdx = minState.lastIndexOf('|')
        val hKey = minState.substring(0, pipeIdx)
        val curAspect = minState.substring(pipeIdx + 1)
        val curHex = parseHexKey(hKey)

        // Expand neighbours
        for (n in neighborsOf(curHex)) {
            if (!isOnBoard(n, board.radius)) continue
            val nk = hexKey(n)
            val ns = board.cells[nk]
            val nsKind = when (ns) {
                null -> "EMPTY"
                is CellState.Empty -> "EMPTY"
                is CellState.Dead -> "DEAD"
                is CellState.Anchor -> "ANCHOR"
                is CellState.Placed -> "PLACED"
            }

            // GOAL CHECK: n is the target anchor itself
            if (n.q == toHex.q && n.r == toHex.r) {
                if (isValidLink(data, curAspect, toAspect)) {
                    // Reconstruct path: only include newly-placed cells (not existing traversal steps).
                    val path = ArrayList<HexAspect>()
                    var cur = minState
                    while (cur != startState) {
                        val p = prev[cur]!!
                        if (!p.existing) path.add(HexAspect(p.hex, p.aspect))
                        cur = p.prevState
                    }
                    path.reverse()
                    return path
                }
                continue // not validly linked to target — don't expand through it
            }

            // DEAD cells cannot be traversed or placed on.
            if (nsKind == "DEAD") continue

            if (nsKind == "PLACED" || nsKind == "ANCHOR") {
                // Already-filled cells can be traversed for free if the current aspect links validly.
                val filledAspect = when (ns) {
                    is CellState.Anchor -> ns.aspect
                    is CellState.Placed -> ns.aspect
                    else -> continue
                }
                if (filledAspect == curAspect) continue // same aspect — invalid link
                if (!isValidLink(data, curAspect, filledAspect)) continue
                val ns2 = "${nk}|$filledAspect"
                if (visited.contains(ns2)) continue
                val newCost = minCost // traversal is free (cell already placed)
                val oldCost = dist[ns2] ?: Double.POSITIVE_INFINITY
                if (newCost < oldCost) {
                    dist[ns2] = newCost
                    prev[ns2] = PrevEntry(minState, n, filledAspect, true)
                }
                continue
            }

            // EMPTY cell: try each aspect for placing at n
            for (b in data.universe) {
                if (b == curAspect) continue // must differ from predecessor
                if (!isValidLink(data, curAspect, b)) continue
                // Validate against all existing filled neighbors of n on the board
                if (!validAgainstBoard(data, board, b, n)) continue

                val ns2 = "${nk}|$b"
                if (visited.contains(ns2)) continue
                val newCost = minCost + obtainCost(inv, data, b, costCache)
                val oldCost = dist[ns2] ?: Double.POSITIVE_INFINITY
                if (newCost < oldCost) {
                    dist[ns2] = newCost
                    prev[ns2] = PrevEntry(minState, n, b, false)
                }
            }
        }
    }
}

/**
 * Returns true if placing aspect `b` at hex `n` is consistent with all existing
 * filled (ANCHOR or PLACED) neighbors of `n` on the board.
 */
private fun validAgainstBoard(data: AspectData, board: Board, b: Aspect, n: Hex): Boolean {
    for (m in neighborsOf(n)) {
        if (!isOnBoard(m, board.radius)) continue
        val ms = board.cells[hexKey(m)] ?: continue // EMPTY
        if (ms !is CellState.Anchor && ms !is CellState.Placed) continue // DEAD/EMPTY impose no constraint
        val ma = when (ms) {
            is CellState.Anchor -> ms.aspect
            is CellState.Placed -> ms.aspect
            else -> continue
        }
        if (ma == b) return false // same aspect adjacent
        if (!isValidLink(data, b, ma)) return false
    }
    return true
}
