package io.github.muindor.tcresearchsolver.solver

const val DEFAULT_THRESHOLD = 50
const val BASE = 1.0
const val K = 1.0

class Inventory(val supply: Map<Aspect, Int>, val threshold: Int = DEFAULT_THRESHOLD)

fun makeInventory(entries: List<Pair<Aspect, Int>>, threshold: Int = DEFAULT_THRESHOLD): Inventory =
    Inventory(LinkedHashMap<Aspect, Int>().apply { entries.forEach { put(it.first, it.second) } }, threshold)

fun validateInventory(inv: Inventory) {
    if (inv.threshold <= 0) throw IllegalArgumentException("threshold must be > 0, got ${inv.threshold}")
    for ((a, n) in inv.supply) {
        if (n < 0) throw IllegalArgumentException("supply['$a'] must be a non-negative integer, got $n")
    }
}

private fun supplyOf(inv: Inventory, a: Aspect): Int = inv.supply[a] ?: 0

fun directPenalty(inv: Inventory, a: Aspect): Double {
    val s = supplyOf(inv, a)
    if (s >= inv.threshold) return 0.0
    if (s > 0) return BASE + K * (inv.threshold - s)
    return Double.POSITIVE_INFINITY
}

/**
 * obtainCost: min(direct penalty, craft-from-components via recipe).
 * Pass a fresh [cache] per solve (or per (inv, data) pair) for memoization (G7).
 * IMPORTANT: never reuse a cache across different (inv, data) pairs — stale costs would break
 * g_lb/globalMinObtain admissibility (mirrors the WeakMap<AspectData, WeakMap<Inventory, …>>
 * nesting in the TS oracle that enforces this automatically).
 */
fun obtainCost(inv: Inventory, data: AspectData, a: Aspect, cache: HashMap<Aspect, Double> = HashMap()): Double {
    fun rec(x: Aspect, stack: HashSet<Aspect>): Double {
        cache[x]?.let { return it }
        if (x in stack) return Double.POSITIVE_INFINITY // cycle guard (data is a DAG; defensive)
        var best = directPenalty(inv, x)
        val recipe = data.combinations[x]
        if (recipe != null) {
            stack.add(x)
            val craft = rec(recipe.first, stack) + rec(recipe.second, stack)
            stack.remove(x)
            if (craft < best) best = craft
        }
        cache[x] = best
        return best
    }
    return rec(a, HashSet())
}

fun globalMinObtain(inv: Inventory, data: AspectData, cache: HashMap<Aspect, Double> = HashMap()): Double {
    var min = Double.POSITIVE_INFINITY
    for (a in data.universe) {
        val c = obtainCost(inv, data, a, cache)
        if (c < min) min = c
    }
    return min
}

enum class Feasible { TRUE, FALSE, UNKNOWN }

class AllocBudget(val maxNodes: Int = 200_000)

class AllocationResult(
    val feasible: Feasible,
    val scarcityCost: Double,
    val craftOps: Int,
    val leafConsumption: Map<Aspect, Int>,
)

/** Reverse-topological order: a parent aspect appears before any of its recipe components. */
private fun reverseTopoOrder(data: AspectData, aspects: Iterable<Aspect>): List<Aspect> {
    val order = ArrayList<Aspect>()
    val seen = HashSet<Aspect>()
    fun visit(a: Aspect) {
        if (!seen.add(a)) return
        data.combinations[a]?.let { recipe ->
            visit(recipe.first)
            visit(recipe.second)
        }
        order.add(a) // components pushed before parent => reverse at the end
    }
    for (a in aspects) visit(a)
    order.reverse() // now parents precede components
    return order
}

private class AllocSub(val cost: Double, val craftOps: Int, val direct: LinkedHashMap<Aspect, Int>)

/**
 * [demand] MUST be an insertion-ordered map (e.g. [LinkedHashMap]) — the TS oracle uses an
 * ordered Map and tie-breaking in the DP depends on the traversal order of demand.keys (G2).
 */
fun allocate(
    inv: Inventory,
    data: AspectData,
    demand: Map<Aspect, Int>,
    budget: AllocBudget = AllocBudget(),
): AllocationResult {
    validateInventory(inv)

    // Aspects relevant to this demand = demand keys plus their full component closure.
    val order = reverseTopoOrder(data, demand.keys)   // parents precede components
    val idx = HashMap<Aspect, Int>().apply { order.forEachIndexed { i, a -> put(a, i) } }
    val n = order.size
    val penalty = DoubleArray(n) { directPenalty(inv, order[it]) }
    val supplyArr = IntArray(n) { inv.supply[order[it]] ?: 0 }
    val need0 = IntArray(n) { demand[order[it]] ?: 0 }

    // Memoized exact DP. rec(i, need) = optimal allocation of indices [i..n) given residual `need`
    // (only entries >= i are meaningful; crafts at j>=i push demand to indices > j by topo order).
    // Memo key = i + suffix-needs => identical subproblems are solved once.
    val memo = HashMap<String, Any>()   // AllocSub or INFEASIBLE sentinel
    val INFEASIBLE = Any()
    var nodes = 0
    var budgetExhausted = false

    fun rec(i: Int, need: IntArray): Any {
        if (budgetExhausted) return INFEASIBLE
        if (i == n) return AllocSub(0.0, 0, LinkedHashMap())
        // Memo key enumerates need[i..n) exactly like TS `need.slice(i).join(',')`
        val key = buildString {
            append(i); append('|')
            for (j in i until n) {
                if (j > i) append(',')
                append(need[j])
            }
        }
        memo[key]?.let { return it }
        if (++nodes > budget.maxNodes) {
            budgetExhausted = true
            return INFEASIBLE
        }

        val x = order[i]; val want = need[i]; val avail = supplyArr[i]; val pen = penalty[i]
        val recipe = data.combinations[x]
        val maxDirect = minOf(want, avail)

        var best: Any = INFEASIBLE

        var d = maxDirect
        while (d >= 0) {
            val c = want - d
            if (c > 0 && recipe == null) { d--; continue }   // primal cannot be crafted
            if (d > 0 && !pen.isFinite()) { d--; continue }  // unreachable (+Inf penalty)
            val need2 = need.copyOf()
            if (c > 0 && recipe != null) {
                // linkedSetOf dedupes a doubled component while mult still multiplies by 2 (G2 + plan note)
                for (comp in linkedSetOf(recipe.first, recipe.second)) {
                    val j = idx.getValue(comp)               // j > i by topo order
                    need2[j] = need2[j] + mult(data, comp, x) * c
                }
            }
            val sub = rec(i + 1, need2)
            if (budgetExhausted) return INFEASIBLE           // BLOCKER-fix: never memoize/return past exhaustion
            if (sub === INFEASIBLE) { d--; continue }
            sub as AllocSub
            val cost = (if (d > 0) d * pen else 0.0) + sub.cost
            val craftOps = c + sub.craftOps
            if (best === INFEASIBLE || cost < (best as AllocSub).cost ||
                (cost == best.cost && craftOps < best.craftOps)) {
                val direct = LinkedHashMap(sub.direct)
                if (d > 0) direct[x] = d
                best = AllocSub(cost, craftOps, direct)
            }
            d--
        }
        memo[key] = best
        return best
    }

    val result = rec(0, need0)

    // Budget exhaustion ALWAYS wins: an interrupted search is unproven, even if a feasible split was seen.
    if (budgetExhausted) {
        return AllocationResult(Feasible.UNKNOWN, Double.POSITIVE_INFINITY, 0, emptyMap())
    }
    if (result === INFEASIBLE) {
        return AllocationResult(Feasible.FALSE, Double.POSITIVE_INFINITY, 0, emptyMap())
    }
    result as AllocSub
    val leaf = LinkedHashMap<Aspect, Int>()
    for ((a, dd) in result.direct) if (dd > 0) leaf[a] = dd
    return AllocationResult(Feasible.TRUE, result.cost, result.craftOps, leaf)
}
