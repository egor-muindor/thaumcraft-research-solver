package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.AspectData
import io.github.muindor.tcresearchsolver.solver.AspectDataError

/**
 * A registry-agnostic description of one aspect entry.
 *
 * Populated from [thaumcraft.api.aspects.Aspect] in [TcTypes.liveRegistryEntries] (which has
 * a TC import) but kept TC-free here so unit tests can construct fake registries.
 *
 * @param tag        stable aspect id, e.g. "ignis", "aer"
 * @param components empty for primals; exactly two element tags for compounds
 * @param isPrimal   true iff the TC Aspect.isPrimal() returns true
 */
data class RegistryEntry(
    val tag: String,
    val components: List<String>,
    val isPrimal: Boolean,
)

/**
 * Build an [AspectData] from a list of registry entries (TC-free, unit-testable).
 *
 * Mirrors the logic of [io.github.muindor.tcresearchsolver.solver.buildAspectData] but uses
 * [entries] as the data source instead of the hard-coded static tables.
 *
 * Invariants enforced (same as buildAspectData):
 *  - No self-referential compound.
 *  - Each component must be a primal or compound key in the same registry.
 *  - No cycles in the component DAG.
 *
 * The [AspectData.translate] field is populated as an identity map (tag → tag) because the live
 * TC registry has no Latin translation table. The solver core never reads translate; it is
 * display-only on the live path.
 */
fun buildAspectDataFrom(entries: List<RegistryEntry>): AspectData {
    // primals: entries with isPrimal, in registry (entries) order
    val primals = LinkedHashSet<String>()
    for (e in entries) {
        if (e.isPrimal) primals.add(e.tag)
    }

    // combinations: compound entries with exactly 2 components, in entries order
    val combos = LinkedHashMap<String, Pair<String, String>>()
    for (e in entries) {
        if (!e.isPrimal && e.components.size == 2) {
            combos[e.tag] = Pair(e.components[0], e.components[1])
        }
    }

    // universe = primals (entries order) ∪ compound key ∪ c0 ∪ c1 (entries order)
    // This mirrors buildAspectData's union order: primals first, then each compound's key+comps.
    val universe = LinkedHashSet<String>(primals)
    for ((k, components) in combos) {
        universe.add(k)
        universe.add(components.first)
        universe.add(components.second)
    }

    // Validation: no self-reference; components must be defined (primal or compound key)
    for ((k, components) in combos) {
        val (c1, c2) = components
        if (c1 == k || c2 == k) throw AspectDataError("aspect '$k' references itself")
        for (c in listOf(c1, c2)) {
            if (!primals.contains(c) && !combos.containsKey(c)) {
                throw AspectDataError("component '$c' of '$k' is not defined")
            }
        }
    }

    // Acyclicity check (same DFS as buildAspectData)
    detectCycleFromRegistry(combos, primals)

    // translate: identity map (tag → tag) for every universe member
    val translate = LinkedHashMap<String, String>()
    for (a in universe) translate[a] = a

    // adjacency: undirected, compound–component edges
    val adjacency = LinkedHashMap<String, LinkedHashSet<String>>()
    for (a in universe) adjacency.getOrPut(a) { LinkedHashSet() }

    fun link(a: String, b: String) {
        adjacency.getOrPut(a) { LinkedHashSet() }.add(b)
        adjacency.getOrPut(b) { LinkedHashSet() }.add(a)
    }
    for ((k, components) in combos) {
        link(k, components.first)
        link(k, components.second)
    }

    // order: primals (entries order) + compound keys (entries order) + any leftover universe member
    val order = ArrayList<String>(primals)
    order.addAll(combos.keys)
    val seen = LinkedHashSet<String>(order)
    for (a in universe) {
        if (!seen.contains(a)) {
            order.add(a)
            seen.add(a)
        }
    }

    return AspectData(
        primals = primals,
        combinations = combos,
        universe = universe,
        translate = translate,
        adjacency = adjacency,
        order = order,
    )
}

private fun detectCycleFromRegistry(
    combos: Map<String, Pair<String, String>>,
    primals: Set<String>,
) {
    // 0=unseen, 1=in-stack, 2=done
    val state = HashMap<String, Int>()

    fun visit(a: String) {
        if (primals.contains(a)) return
        val s = state[a] ?: 0
        if (s == 2) return
        if (s == 1) throw AspectDataError("cycle detected through aspect '$a'")
        state[a] = 1
        val recipe = combos[a]
        if (recipe != null) {
            visit(recipe.first)
            visit(recipe.second)
        }
        state[a] = 2
    }

    for (a in combos.keys) visit(a)
}

/**
 * Reads the live in-game TC registry and builds an [AspectData].
 *
 * **Only call in-game** — the TC registry is not populated in unit tests.
 * The TC import lives in [TcTypes.liveRegistryEntries] so this file stays import-free of TC.
 */
object AspectDataProvider {
    fun fromLiveRegistry(): AspectData = buildAspectDataFrom(liveRegistryEntries())
}
