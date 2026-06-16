package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.component.BackgroundUIComponent
import elan.tweaks.common.gui.component.TickingUIComponent
import elan.tweaks.common.gui.component.UIContext
import elan.tweaks.common.gui.dto.VectorXY

/**
 * Shows an animated spinner + progress line while the controller is Solving; invisible otherwise.
 * @param origin top-left text anchor, supplied by the Mixin (Task 4.7).
 */
class SpinnerComponent(
    private val origin: VectorXY,
    private val controller: SolveController,
) : BackgroundUIComponent, TickingUIComponent {

    private val frames = charArrayOf('|', '/', '-', '\\')
    private var tickCounter = 0
    private var frame = 0

    override fun onTick(partialTicks: Float, ctx: UIContext) {
        if (controller.state !is SolveState.Solving) {
            tickCounter = 0
            frame = 0
            return
        }
        // Advance the spinner ~every 3 ticks (do NOT pump the worker here — the button does that)
        if (++tickCounter % 3 == 0) frame = (frame + 1) % frames.size
    }

    override fun onDrawBackground(mouse: VectorXY, partialTicks: Float, ctx: UIContext) {
        if (controller.state !is SolveState.Solving) return   // invisible unless solving
        val text = "${frames[frame]} " + (controller.progressText() ?: "Solving…")
        ctx.drawWithShadow(text, origin)
    }
}
