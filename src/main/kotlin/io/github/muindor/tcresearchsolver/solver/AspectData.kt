package io.github.muindor.tcresearchsolver.solver

typealias Aspect = String

class AspectData(
    val primals: Set<Aspect>,                          // LinkedHashSet
    val combinations: Map<Aspect, Pair<Aspect, Aspect>>, // LinkedHashMap
    val universe: Set<Aspect>,                         // LinkedHashSet, insertion order
    val translate: Map<Aspect, String>,                // LinkedHashMap
    val adjacency: Map<Aspect, Set<Aspect>>,           // LinkedHashMap of LinkedHashSet
    val order: List<Aspect>,
)

class AspectDataError(message: String) : RuntimeException(message)

class BuildOptions(
    val addons: List<String>? = null,                  // default fm,mb,gt,tb,av
    val overrideCombinations: Map<String, Pair<Aspect, Aspect>>? = null,
    val overrideTranslate: Map<String, String>? = null,
    val overrideDeclaredAspects: List<String>? = null,
)

fun buildAspectData(opts: BuildOptions = BuildOptions()): AspectData {
    val primals = LinkedHashSet<Aspect>(PRIMALS)

    val combos = LinkedHashMap<Aspect, Pair<Aspect, Aspect>>()
    // Spec §2.1: the universe explicitly unions addon-declared aspects, not only recipe keys/components.
    val declared = LinkedHashSet<Aspect>()

    if (opts.overrideCombinations != null) {
        for ((k, v) in opts.overrideCombinations) combos[k] = v
        for (a in opts.overrideDeclaredAspects ?: emptyList()) declared.add(a)
    } else {
        for ((k, v) in COMBINATIONS_4_2_2_0) combos[k] = v
        for (id in opts.addons ?: listOf("fm", "mb", "gt", "tb", "av")) {
            val addon = ADDONS[id] ?: throw AspectDataError("unknown addon '$id'")
            for (a in addon.aspects) declared.add(a)
            for ((k, v) in addon.combinations) combos[k] = v
        }
    }

    // Universe = primals ∪ declared addon aspects ∪ compound keys ∪ all components (spec §2.1).
    val universe = LinkedHashSet<Aspect>(primals)
    for (a in declared) universe.add(a)
    for ((k, components) in combos) {
        universe.add(k)
        universe.add(components.first)
        universe.add(components.second)
    }

    // No self-reference; components must be defined (primal or compound key).
    for ((k, components) in combos) {
        val (c1, c2) = components
        if (c1 == k || c2 == k) throw AspectDataError("aspect '$k' references itself")
        for (c in listOf(c1, c2)) {
            if (!primals.contains(c) && !combos.containsKey(c)) {
                throw AspectDataError("component '$c' of '$k' is not defined")
            }
        }
    }

    // Acyclicity of the "is-component-of" DAG (compound depends on its components).
    detectCycle(combos, primals)

    // translate for every universe member. In override (test) mode, callers may supply
    // overrideTranslate for synthetic aspects; any aspect still missing a translation throws.
    val translate = LinkedHashMap<Aspect, String>()
    for (a in universe) {
        val latin = (TRANSLATE[a] ?: opts.overrideTranslate?.get(a))?.takeIf { it.isNotEmpty() }
            ?: throw AspectDataError("aspect '$a' has no translation/icon mapping")
        translate[a] = latin
    }

    // Undirected adjacency (deduped Set): edge compound–component (spec §2.1).
    val adjacency = LinkedHashMap<Aspect, LinkedHashSet<Aspect>>()
    for (a in universe) if (!adjacency.containsKey(a)) adjacency[a] = LinkedHashSet()

    fun link(a: Aspect, b: Aspect) {
        adjacency.getOrPut(a) { LinkedHashSet() }.add(b)
        adjacency.getOrPut(b) { LinkedHashSet() }.add(a)
    }

    for ((k, components) in combos) {
        link(k, components.first)
        link(k, components.second)
    }

    // Display order: primals (PRIMALS order), then compounds in registration/tier order
    // (base Thaumcraft decl order, then addons), then any declared-only aspect as a fallback.
    val order = ArrayList<Aspect>(primals)
    order.addAll(combos.keys)
    val seen = LinkedHashSet<Aspect>(order)
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

private fun detectCycle(
    combos: Map<Aspect, Pair<Aspect, Aspect>>,
    primals: Set<Aspect>,
) {
    // 0=unseen, 1=in-stack, 2=done
    val state = HashMap<Aspect, Int>()

    fun visit(a: Aspect) {
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

fun iconLatin(data: AspectData, a: Aspect): String {
    return data.translate[a]?.takeIf { it.isNotEmpty() } ?: throw AspectDataError("no icon for '$a'")
}
