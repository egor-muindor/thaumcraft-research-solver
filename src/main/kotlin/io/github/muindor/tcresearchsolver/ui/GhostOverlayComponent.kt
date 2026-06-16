package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.component.BackgroundUIComponent
import elan.tweaks.common.gui.component.UIContext
import elan.tweaks.common.gui.dto.VectorXY
import io.github.muindor.tcresearchsolver.solver.CellState
import io.github.muindor.tcresearchsolver.solver.SolverStatus
import io.github.muindor.tcresearchsolver.solver.boardCells
import io.github.muindor.tcresearchsolver.solver.getState
import io.github.muindor.tcresearchsolver.solver.hexKey

/**
 * Draws the previewed solution as translucent aspect icons while the controller is in Preview.
 *
 * @param controller        the state machine; ghosts render only while state is Preview.
 * @param hexCenter         maps a solver hexKey "q,r" -> the pixel center VectorXY of that cell
 *                          on screen. Supplied by the Mixin (Task 4.7), backed by
 *                          ParchmentHexMapLayout. Returns null if the key isn't on the layout.
 * @param metadataOrigin    where to draw the "optimal / best-found" status line.
 */
class GhostOverlayComponent(
    private val controller: SolveController,
    private val hexCenter: (hexKey: String) -> VectorXY?,
    private val metadataOrigin: VectorXY,
) : BackgroundUIComponent {

    private companion object {
        // GL_ONE_MINUS_SRC_ALPHA — the blend func RT uses for aspect tags (AspectRenderer.BLEND).
        const val BLEND = 771
        // Translucency for the preview icons (Float 0..1; NOT the 0..255 we wrongly passed before,
        // which `drawTag(aspect, amount, pos)` rendered as the "120" count number).
        const val GHOST_ALPHA = 0.5f
    }

    // Background (not Foreground): RT's foreground draw pass runs inside vanilla's extra
    // glTranslate(guiLeft,guiTop) while TableUIContext ALSO adds that origin → foreground components
    // double-offset. As a background appended last to the list, this draws on top of the hex grid
    // with the correct single offset (same convention as RT's own AspectHexMapUIComponent).
    override fun onDrawBackground(mouse: VectorXY, partialTicks: Float, ctx: UIContext) {
        val state = controller.state
        if (state !is SolveState.Preview) return
        val board = state.result.board ?: return

        for (h in boardCells(board.radius)) {
            val cell = getState(board, h)
            if (cell is CellState.Placed && !cell.locked) {
                val pos = hexCenter(hexKey(h)) ?: continue
                val aspect = thaumcraft.api.aspects.Aspect.getAspect(cell.aspect) ?: continue
                // amount=0 → no count number; translucent, colored icon at the hex draw origin.
                ctx.drawTag(aspect, 0, 0, BLEND, GHOST_ALPHA, pos)
            }
        }

        // Metadata line
        val label = when (state.result.status) {
            SolverStatus.OPTIMAL -> "Optimal solution"
            SolverStatus.FEASIBLE_TIMEOUT -> "Best found (timeout)"
            else -> "Status: ${state.result.status}"
        }
        ctx.drawWithShadow(label, metadataOrigin)
    }
}
