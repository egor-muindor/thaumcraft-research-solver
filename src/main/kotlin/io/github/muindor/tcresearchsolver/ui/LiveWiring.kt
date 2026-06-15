package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.dto.VectorXY
import io.github.muindor.tcresearchsolver.integration.AspectDataProvider
import io.github.muindor.tcresearchsolver.integration.BoardReader
import io.github.muindor.tcresearchsolver.integration.InventoryReader
import io.github.muindor.tcresearchsolver.integration.planApply
import io.github.muindor.tcresearchsolver.integration.Applier
import io.github.muindor.tcresearchsolver.solver.AspectData
import io.github.muindor.tcresearchsolver.solver.DEFAULT_THRESHOLD
import io.github.muindor.tcresearchsolver.solver.SolveBudget
import io.github.muindor.tcresearchsolver.solver.SolveResult
import io.github.muindor.tcresearchsolver.solver.budgetForRadius

// ---------------------------------------------------------------------------
// Live aspect data (lazy singleton — TC registry is fixed after FML load)
// ---------------------------------------------------------------------------

/** AspectData from the live registry, built once (registry is fixed at runtime). */
object LiveAspectData {
    val data: AspectData by lazy { AspectDataProvider.fromLiveRegistry() }
}

// ---------------------------------------------------------------------------
// Snapshot builder
// ---------------------------------------------------------------------------

/**
 * Builds a fresh [SolveSnapshot] from the live table state, or null if nothing to solve.
 *
 * Called on the client thread immediately before handing the snapshot to [SolveController].
 * Reading [thaumcraft.common.tiles.TileResearchTable.data] (public field) directly avoids
 * the mangled ResearchNotesAdapter write-API entirely.
 */
fun buildSnapshot(
    player: net.minecraft.entity.player.EntityPlayer,
    tile: thaumcraft.common.tiles.TileResearchTable,
): SolveSnapshot? {
    val noteData = tile.data ?: return null
    if (noteData.complete) return null          // already solved — nothing to do
    if (noteData.hexes.isEmpty()) return null   // no grid shape yet

    val data = LiveAspectData.data
    val board = BoardReader.fromNoteData(noteData)
    val pool = elan.tweaks.thaumcraft.research.frontend.integration.adapters.AspectPoolAdapter(player, tile)
    val inventory = InventoryReader.read(pool, data, DEFAULT_THRESHOLD)
    val base = budgetForRadius(board.radius)
    val ms = io.github.muindor.tcresearchsolver.config.Config.maxSolveMs
    val budget = if (ms > 0) SolveBudget(base.maxNodes, ms.toLong(), base.beam) else base
    return SolveSnapshot(data, board, inventory, budget, inventory.supply)
}

// ---------------------------------------------------------------------------
// Live ApplierPort
// ---------------------------------------------------------------------------

/**
 * Real [ApplierPort]: plans the operations, sends TC packets, reports outcome.
 *
 * Post-verify (re-reading tile.data after server sync) is deferred to Phase 5 (runClient).
 * For now we report optimistic success after a non-aborted apply.
 */
class LiveApplierPort(
    private val player: net.minecraft.entity.player.EntityPlayer,
    private val tile: thaumcraft.common.tiles.TileResearchTable,
) : ApplierPort {
    override fun apply(result: SolveResult, snapshot: SolveSnapshot, onDone: (ApplyReport) -> Unit) {
        val plan = planApply(result, snapshot.data, snapshot.pool)
        val outcome = Applier.apply(plan, player, tile)
        if (!outcome.applied) {
            onDone(ApplyReport(emptySet(), abortReason = outcome.reason))
            return
        }
        // First cut: optimistic success. Real post-verify needs a deferred re-read of tile.data
        // (server processes packets asynchronously) — flagged for runClient (Phase 5).
        onDone(ApplyReport(emptySet()))
    }
}

// ---------------------------------------------------------------------------
// Hex → pixel layout (reflective access to ParchmentHexMapLayout.keyToHex)
// ---------------------------------------------------------------------------

/**
 * Maps hex key "q,r" → pixel center [VectorXY] via [ParchmentHexMapLayout]'s private static
 * `keyToHex` map (exposed reflectively to avoid needing an extra mixin accessor at this stage).
 *
 * ⚠️ runClient verify: confirm that the key format equals the solver's "q,r" and that getCenter()
 * aligns with the rendered hex grid at multiple window sizes (Phase 5).
 */
object HexPixelLayout {
    private val keyToHex: Map<*, *> by lazy {
        val cls = Class.forName(
            "elan.tweaks.thaumcraft.research.frontend.integration.table.gui.layout.ParchmentHexMapLayout"
        )
        val f = cls.getDeclaredField("keyToHex")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        f.get(null) as Map<*, *>
    }

    fun center(hexKey: String): VectorXY? {
        val hex = keyToHex[hexKey] ?: return null
        val getCenter = hex.javaClass.getMethod("getCenter")
        return getCenter.invoke(hex) as? VectorXY
    }
}
