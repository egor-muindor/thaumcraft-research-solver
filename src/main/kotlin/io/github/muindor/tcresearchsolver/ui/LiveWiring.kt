package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.dto.Vector2D
import elan.tweaks.common.gui.dto.VectorXY
import io.github.muindor.tcresearchsolver.integration.AspectDataProvider
import io.github.muindor.tcresearchsolver.integration.BoardReader
import io.github.muindor.tcresearchsolver.integration.InventoryReader
import io.github.muindor.tcresearchsolver.integration.planApply
import io.github.muindor.tcresearchsolver.integration.Applier
import io.github.muindor.tcresearchsolver.solver.AspectData
import io.github.muindor.tcresearchsolver.solver.DEFAULT_THRESHOLD
import io.github.muindor.tcresearchsolver.solver.SolveResult
import io.github.muindor.tcresearchsolver.solver.budgetForRadius
import io.github.muindor.tcresearchsolver.solver.resolveBudget

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
 *
 * The note is read from the **slot-1 research-note ItemStack** via
 * [thaumcraft.common.lib.research.ResearchManager.getData] — the same source RT uses to render
 * the hex grid. `TileResearchTable.data` is a server-only cache that the tile never
 * network-serializes (`writeCustomNBT` omits it), so it is null/stale on the client.
 */
fun buildSnapshot(
    player: net.minecraft.entity.player.EntityPlayer,
    tile: thaumcraft.common.tiles.TileResearchTable,
): SolveSnapshot? {
    // FIX (Phase 5.2): read the note from the slot-1 research-note ItemStack — container-synced
    // to the client — instead of tile.data (server-only cache, never network-serialized).
    val note = tile.getStackInSlot(1)
    val noteData = note?.let { thaumcraft.common.lib.research.ResearchManager.getData(it) } ?: return null
    if (noteData.complete) return null
    if (noteData.hexes.isEmpty()) return null

    val data = LiveAspectData.data
    val board = BoardReader.fromNoteData(noteData)
    val pool = elan.tweaks.thaumcraft.research.frontend.integration.adapters.AspectPoolAdapter(player, tile)
    val inventory = InventoryReader.read(pool, data, DEFAULT_THRESHOLD)

    val config = io.github.muindor.tcresearchsolver.config.Config
    val budget = resolveBudget(
        budgetForRadius(board.radius),
        config.maxSolveMsForRadius(board.radius),
        config.maxSolveMs,
    )
    return SolveSnapshot(data, board, inventory, budget, inventory.supply, fast = config.fastSolve)
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
// Hex → pixel layout (RT's own axial→pixel formula)
// ---------------------------------------------------------------------------

/**
 * Maps a solver hex key `"q,r"` (comma) to the GUI-local draw origin of that hex's aspect tag,
 * using the same math RT's live grid renders through:
 *   center = HexMath.toCenterVector(q, r, hexSize=9) + centerUiOrigin
 *   origin = center - (tagHalf, tagHalf)            // RT's toOrigin(center) == center - 8
 * [TableUIContext] adds `(guiLeft, guiTop)` at draw time, so this stays GUI-local.
 *
 * Values pinned against the ResearchTweaks 1.3.0 decompile:
 *   centerUiOrigin = ResearchArea.bounds.origin (96,35) + ParchmentTexture.centerOrigin (75,75)
 *                  = (171, 110)
 *   hexSize = 9, tag is 16px so toOrigin subtracts 8 to centre the icon on the hex.
 *
 * The previous reflective `ParchmentHexMapLayout.keyToHex` lookup was wrong: that map is the
 * decorative-rune candidate set, is keyed `"q:r"` (colon), is parchment-center-relative, and
 * omits `"0:0"` — so it returned null for every solver key.
 */
object HexPixelLayout {
    private const val HEX_SIZE = 9.0
    private const val CENTER_X = 171
    private const val CENTER_Y = 110
    private const val TAG_HALF = 8

    fun center(hexKey: String): VectorXY? {
        val parts = hexKey.split(',')
        if (parts.size != 2) return null
        val q = parts[0].trim().toIntOrNull() ?: return null
        val r = parts[1].trim().toIntOrNull() ?: return null
        val cx = Math.round(HEX_SIZE * 1.5 * q).toInt() + CENTER_X - TAG_HALF
        val cy = Math.round(HEX_SIZE * Math.sqrt(3.0) * (r + q / 2.0)).toInt() + CENTER_Y - TAG_HALF
        return Vector2D(cx, cy)
    }
}
