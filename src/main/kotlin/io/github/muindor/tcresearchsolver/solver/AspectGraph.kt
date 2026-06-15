package io.github.muindor.tcresearchsolver.solver

/**
 * Returns a defensive copy of the adjacency set for [a].
 * Callers may not mutate the returned set.
 */
fun neighbors(data: AspectData, a: Aspect): Set<Aspect> {
    val backing = data.adjacency[a]
    return if (backing != null) LinkedHashSet(backing) else LinkedHashSet()
}

/**
 * Hot path (called per candidate in the solver): reads the backing set directly, no copy.
 * Returns false if [a] == [b] (no self-links).
 */
fun isValidLink(data: AspectData, a: Aspect, b: Aspect): Boolean {
    if (a == b) return false
    return data.adjacency[a]?.contains(b) ?: false
}

/**
 * Direct multiplicity of component [x] in the recipe of [y] (0, 1, or 2).
 * Returns 0 if [y] has no recipe or [x] is not a component.
 */
fun mult(data: AspectData, x: Aspect, y: Aspect): Int {
    val recipe = data.combinations[y] ?: return 0
    return (if (recipe.first == x) 1 else 0) + (if (recipe.second == x) 1 else 0)
}

/**
 * Returns the primal decomposition of [a] as a map from each primal to its total count.
 * The returned map is a defensive copy — callers may mutate it without corrupting the cache.
 * Throws [IllegalArgumentException] if [a] is neither primal nor compound.
 */
fun primalVec(data: AspectData, a: Aspect): Map<Aspect, Int> {
    val cache = data.primalVecCache
    val cached = cache[a]
    if (cached != null) return LinkedHashMap(cached)

    val result: LinkedHashMap<Aspect, Int>
    if (data.primals.contains(a)) {
        result = LinkedHashMap<Aspect, Int>().apply { put(a, 1) }
    } else {
        val recipe = data.combinations[a]
            ?: throw IllegalArgumentException("aspect '$a' is neither primal nor a compound")
        result = LinkedHashMap()
        // recipe is a Pair<Aspect, Aspect>; iterate both components (may be duplicate for doubled recipes)
        for (c in listOf(recipe.first, recipe.second)) {
            for ((p, n) in primalVec(data, c)) {
                result[p] = (result[p] ?: 0) + n
            }
        }
    }
    cache[a] = result
    return LinkedHashMap(result)
}
