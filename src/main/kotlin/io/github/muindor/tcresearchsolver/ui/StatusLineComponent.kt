package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.component.BackgroundUIComponent
import elan.tweaks.common.gui.component.UIContext
import elan.tweaks.common.gui.dto.VectorXY

/**
 * Shows the solve progress line while the controller is Solving; invisible otherwise.
 *
 * No animated spinner: the rotating `| / - \` glyphs have different widths in the
 * Minecraft font, so every frame nudged the whole line left/right and it visibly
 * jittered. The climbing `⏱ s · nodes` counters already signal that work is in
 * progress, so the leading glyph is dropped and the line stays still.
 *
 * @param origin top-left text anchor, supplied by the Mixin.
 */
class StatusLineComponent(
    private val origin: VectorXY,
    private val controller: SolveController,
) : BackgroundUIComponent {

    override fun onDrawBackground(mouse: VectorXY, partialTicks: Float, ctx: UIContext) {
        if (controller.state !is SolveState.Solving) return   // invisible unless solving
        ctx.drawWithShadow(controller.progressText() ?: "Solving…", origin)
    }
}
