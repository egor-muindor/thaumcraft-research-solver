package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.AspectData
import io.github.muindor.tcresearchsolver.solver.DEFAULT_THRESHOLD
import io.github.muindor.tcresearchsolver.solver.Inventory

// ---------------------------------------------------------------------------
// Pure mapper — no TC / RT imports; unit-testable
// ---------------------------------------------------------------------------

/**
 * Builds an [Inventory] from a pre-computed [amounts] map and a [threshold].
 *
 * The [amounts] map contains only *discovered* aspects (supply > 0 or explicitly 0).
 * Aspects absent from [amounts] will produce null in [Inventory.supply], which the
 * solver interprets as supply 0 via `supply[a] ?: 0`.
 *
 * Insertion order of [amounts] is preserved in the resulting [Inventory.supply].
 * Validation (threshold > 0, no negative supply) is intentionally NOT performed here —
 * the solver calls [io.github.muindor.tcresearchsolver.solver.validateInventory] itself.
 */
fun toInventory(amounts: Map<String, Int>, threshold: Int): Inventory =
    Inventory(LinkedHashMap(amounts), threshold)

// ---------------------------------------------------------------------------
// TC / RT-touching layer — InventoryReader.read
// Keep TC and RT imports below this line so toInventory stays import-free.
// ---------------------------------------------------------------------------

/**
 * Reads a live [elan.tweaks.thaumcraft.research.frontend.domain.ports.required.AspectPool]
 * and builds a solver [Inventory].
 *
 * For each aspect tag in [data.universe] (in insertion order):
 *  - Looks up the live TC [thaumcraft.api.aspects.Aspect] via [Aspect.getAspect].
 *  - Skips the tag if the aspect is null (not registered — defensive; should not occur for a
 *    registry-derived universe).
 *  - Skips undiscovered aspects (`pool.hasDiscovered` returns false) — they contribute 0 supply
 *    and are absent from the returned [Inventory.supply]; the solver uses `supply[a] ?: 0`.
 *  - Otherwise records `tag -> pool.totalAmountOf(aspect)` in universe iteration order.
 *
 * **Only call in-game** — this method requires a live TC registry and an RT AspectPool instance.
 *
 * @param pool      the RT aspect pool for the current research session
 * @param data      the solver's registry-derived [AspectData]; its [AspectData.universe] drives iteration
 * @param threshold the inventory threshold forwarded to [Inventory.threshold] (default [DEFAULT_THRESHOLD])
 */
object InventoryReader {
    fun read(
        pool: elan.tweaks.thaumcraft.research.frontend.domain.ports.required.AspectPool,
        data: AspectData,
        threshold: Int = DEFAULT_THRESHOLD,
    ): Inventory {
        val amounts = LinkedHashMap<String, Int>()
        for (tag in data.universe) {
            val aspect = thaumcraft.api.aspects.Aspect.getAspect(tag) ?: continue
            if (!pool.hasDiscovered(aspect)) continue
            amounts[tag] = pool.totalAmountOf(aspect)
        }
        return toInventory(amounts, threshold)
    }
}
