package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.*

// ---------------------------------------------------------------------------
// Pure data model — no TC / RT imports; unit-testable
// ---------------------------------------------------------------------------

/**
 * One operation in the apply plan.
 *
 * [Combine] asks the server to combine [a] + [b] in the working pool
 *   → maps to [thaumcraft.common.lib.network.playerdata.PacketAspectCombinationToServer].
 * [Place] asks the server to write [aspect] at grid cell [key] ("q,r")
 *   → maps to [thaumcraft.common.lib.network.playerdata.PacketAspectPlaceToServer].
 */
sealed class ApplyOp {
    data class Combine(val a: Aspect, val b: Aspect) : ApplyOp()
    data class Place(val key: String, val aspect: Aspect) : ApplyOp()
}

/**
 * Plan the ordered list of [ApplyOp]s needed to apply [result] given the live [pool].
 *
 * Pure function — no TC / RT imports, fully unit-testable.
 *
 * Algorithm (see task spec):
 *  1. Collect solver-placed cells (Placed && !locked) from [result.board], sorted by hexKey.
 *  2. For each placement, call [obtain] which recursively emits [ApplyOp.Combine] ops for any
 *     crafting needed (deepest components first), then appends [ApplyOp.Place].
 *
 * The [pool] map is copied; original is not mutated.
 * Returns an empty list if [result.board] is null.
 */
fun planApply(
    result: SolveResult,
    data: AspectData,
    pool: Map<Aspect, Int>,
): List<ApplyOp> {
    val board = result.board ?: return emptyList()

    // Collect solver placements: Placed && !locked
    val placements = mutableListOf<Pair<String, Aspect>>()
    for (h in boardCells(board.radius)) {
        val state = getState(board, h)
        if (state is CellState.Placed && !state.locked) {
            placements.add(hexKey(h) to state.aspect)
        }
    }

    // Sort by hexKey ascending (String compareTo — spec gotcha G6)
    placements.sortBy { it.first }

    // Mutable working copy of the pool
    val work = LinkedHashMap<Aspect, Int>()
    for ((a, n) in pool) work[a] = n

    val ops = mutableListOf<ApplyOp>()

    // Obtain 1 unit of [asp]: draw from work if available, otherwise recursively craft it.
    // Emits Combine ops (deepest-first) before consuming.
    // [asp] is not added to work after crafting — the produced unit is immediately consumed by
    // the calling placement or parent Combine.
    fun obtain(asp: Aspect) {
        val available = work[asp] ?: 0
        if (available > 0) {
            work[asp] = available - 1
            return
        }
        val recipe = data.combinations[asp]
            ?: throw IllegalStateException(
                "cannot obtain primal '$asp' — pool exhausted (should not happen for a feasible solve)"
            )
        val (c1, c2) = recipe
        // Children (deepest) before parent (spec: deepest components first)
        obtain(c1)
        obtain(c2)
        ops.add(ApplyOp.Combine(c1, c2))
        // Do NOT add asp to work: produced unit is consumed by caller
    }

    for ((key, asp) in placements) {
        obtain(asp)               // emits needed Combine ops first
        ops.add(ApplyOp.Place(key, asp))
    }

    return ops
}

// ---------------------------------------------------------------------------
// Result type for Applier.apply
// ---------------------------------------------------------------------------

data class ApplyOutcome(val applied: Boolean, val reason: String? = null)

// ---------------------------------------------------------------------------
// TC / RT-touching layer — Applier object
// Keep ALL TC / RT imports confined below this line so planApply stays testable.
// ---------------------------------------------------------------------------

/**
 * Executes an [ApplyOp] plan by sending TC packets on the client thread.
 *
 * **Only call in-game** — this object invokes Thaumcraft and ResearchTweaks classes
 * not available in unit tests.
 */
object Applier {

    /**
     * Execute [plan] on the client thread.
     *
     * Gates on ink presence via [elan.tweaks.thaumcraft.research.frontend.integration.adapters.ScribeToolsAdapter].
     * For each op, sends the corresponding TC packet via [thaumcraft.common.lib.network.PacketHandler.INSTANCE].
     *
     * @return [ApplyOutcome] describing success or the reason for abort.
     */
    fun apply(
        plan: List<ApplyOp>,
        player: net.minecraft.entity.player.EntityPlayer,
        tile: thaumcraft.common.tiles.TileResearchTable,
    ): ApplyOutcome {
        // Ink gate
        if (elan.tweaks.thaumcraft.research.frontend.integration.adapters.ScribeToolsAdapter(tile)
                .areMissingOrEmpty()
        ) {
            return ApplyOutcome(false, "scribing tools missing or empty")
        }

        val x = tile.xCoord
        val y = tile.yCoord
        val z = tile.zCoord

        for (op in plan) {
            when (op) {
                is ApplyOp.Combine -> {
                    val a1 = thaumcraft.api.aspects.Aspect.getAspect(op.a)
                    val a2 = thaumcraft.api.aspects.Aspect.getAspect(op.b)
                    thaumcraft.common.lib.network.PacketHandler.INSTANCE.sendToServer(
                        thaumcraft.common.lib.network.playerdata.PacketAspectCombinationToServer(
                            player, x, y, z, a1, a2,
                            false, false, false,
                        )
                    )
                }
                is ApplyOp.Place -> {
                    val h = parseHexKey(op.key)
                    val asp = thaumcraft.api.aspects.Aspect.getAspect(op.aspect)
                    thaumcraft.common.lib.network.PacketHandler.INSTANCE.sendToServer(
                        thaumcraft.common.lib.network.playerdata.PacketAspectPlaceToServer(
                            player, h.q.toByte(), h.r.toByte(), x, y, z, asp,
                        )
                    )
                }
            }
        }

        return ApplyOutcome(true)
    }

    /**
     * Re-read [note] and return the set of [ApplyOp.Place] keys whose aspect was not accepted
     * by the server (absent from hexEntries or aspect tag mismatch).
     *
     * Call after a short delay to allow server sync packets to arrive.
     */
    fun postVerify(note: net.minecraft.item.ItemStack, plan: List<ApplyOp>): Set<String> {
        val data = thaumcraft.common.lib.research.ResearchManager.getData(note)
        val placed = plan.filterIsInstance<ApplyOp.Place>()
        return placed
            .filter { p ->
                val entry = data.hexEntries[p.key]
                entry == null || entry.aspect?.tag != p.aspect
            }
            .map { it.key }
            .toSet()
    }
}
