package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.component.ForegroundUIComponent
import elan.tweaks.common.gui.component.UIContext
import elan.tweaks.common.gui.dto.Scale
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
) : ForegroundUIComponent {

    private val ghostAlpha = 120

    override fun onDrawForeground(mouse: VectorXY, scale: Scale, ctx: UIContext) {
        val state = controller.state
        if (state !is SolveState.Preview) return
        val board = state.result.board ?: return

        for (h in boardCells(board.radius)) {
            val cell = getState(board, h)
            if (cell is CellState.Placed && !cell.locked) {
                val pos = hexCenter(hexKey(h)) ?: continue
                val aspect = thaumcraft.api.aspects.Aspect.getAspect(cell.aspect) ?: continue
                ctx.drawTag(aspect, ghostAlpha, pos)
            }
        }

        // Metadata line
        val label = when (state.result.status) {
            SolverStatus.OPTIMAL -> "Solution: optimal"
            SolverStatus.FEASIBLE_TIMEOUT -> "Solution: best found (not proven optimal)"
            else -> "Solution: ${state.result.status}"
        }
        ctx.drawWithShadow(label, metadataOrigin)
    }
}
