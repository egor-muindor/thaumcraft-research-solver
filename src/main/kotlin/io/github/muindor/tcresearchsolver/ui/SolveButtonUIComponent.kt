package io.github.muindor.tcresearchsolver.ui

import elan.tweaks.common.gui.component.BackgroundUIComponent
import elan.tweaks.common.gui.component.ClickableUIComponent
import elan.tweaks.common.gui.component.MouseOverUIComponent
import elan.tweaks.common.gui.component.TickingUIComponent
import elan.tweaks.common.gui.component.UIContext
import elan.tweaks.common.gui.dto.Rectangle
import elan.tweaks.common.gui.dto.VectorXY
import elan.tweaks.common.gui.peripheral.MouseButton
import io.github.muindor.tcresearchsolver.TcResearchSolverMod
import org.apache.logging.log4j.LogManager

/**
 * A clickable "Solve / Cancel / Apply / Reset" button that mirrors the shape of
 * `CopyButtonUIComponent` from ResearchTweaks.
 *
 * Implements [BackgroundUIComponent] + [MouseOverUIComponent] + [ClickableUIComponent]
 * + [TickingUIComponent] — the same four interfaces as the RT copy button.
 *
 * Threading: all methods are called on the **client (render) thread**.
 * [onTick] calls [SolveWorker.pump] to deliver background-thread results to [controller].
 *
 * @param bounds           Hit-test rectangle; supplied by the Mixin (Task 4.7).
 * @param controller       Pure state machine that owns Solve/Cancel/Apply/Reset logic.
 * @param worker           Background thread wrapper whose [SolveWorker.pump] must be
 *                         called each tick to forward results to [controller].
 * @param snapshotProvider Builds a fresh [SolveSnapshot] from live MC state.
 *                         Called on the client thread immediately before [controller.start].
 *                         May return `null` if the note is not currently solvable.
 */
class SolveButtonUIComponent(
    private val bounds: Rectangle,
    private val controller: SolveController,
    private val worker: SolveWorker,
    private val snapshotProvider: () -> SolveSnapshot?,
) : BackgroundUIComponent, MouseOverUIComponent, ClickableUIComponent, TickingUIComponent {

    // ------------------------------------------------------------------
    // TickingUIComponent
    // ------------------------------------------------------------------

    /**
     * Pump the worker on the client thread each tick so that background-thread
     * progress / done callbacks are delivered to [controller].
     */
    override fun onTick(partialTicks: Float, ctx: UIContext) {
        worker.pump()
    }

    // ------------------------------------------------------------------
    // BackgroundUIComponent
    // ------------------------------------------------------------------

    /**
     * Render the state-driven text label at the button origin.
     *
     * Full texture styling is deferred to Phase 5 runClient polish.
     * The text is greyed out when the button is disabled (mid-Applying).
     */
    override fun onDrawBackground(mouse: VectorXY, partialTicks: Float, ctx: UIContext) {
        val label = controller.buttonLabel()
        ctx.drawWithShadow(label, bounds.origin)
    }

    // ------------------------------------------------------------------
    // ClickableUIComponent
    // ------------------------------------------------------------------

    /**
     * Dispatch the click to [controller] when the pointer is inside [bounds]
     * and the button is [enabled].
     *
     * For [SolveState.Idle] → start, we build a fresh snapshot on this call
     * (client thread) and stash it on [controller.snapshot] before delegating
     * to [controller.onButtonClicked].  If the snapshot comes back null (note
     * not solvable / no tile in range) the click is silently ignored.
     */
    override fun onMouseClicked(mouse: VectorXY, button: MouseButton, ctx: UIContext) {
        if (!bounds.contains(mouse)) return
        if (!enabled()) return

        if (controller.state is SolveState.Idle) {
            val snap = try {
                snapshotProvider()
            } catch (t: Throwable) {
                // A snapshot-build failure must not crash the RT GUI: log once and ignore the click.
                log.error("Solve snapshot build failed", t)
                return
            }
            if (snap == null) return // note not currently solvable
            controller.snapshot = snap
        }

        controller.onButtonClicked()
    }

    // ------------------------------------------------------------------
    // MouseOverUIComponent
    // ------------------------------------------------------------------

    /**
     * Show a context-sensitive tooltip when the pointer hovers inside [bounds]:
     * - Solving  → progress text (nodes explored, elapsed, best so far)
     * - Error    → the error message so the player can see what went wrong
     * - Preview  → hint to click and apply
     * - All else → generic hint
     */
    override fun onMouseOver(mouse: VectorXY, partialTicks: Float, ctx: UIContext) {
        if (!bounds.contains(mouse)) return

        val line = when (val s = controller.state) {
            is SolveState.Solving -> controller.progressText() ?: "Solving…"
            is SolveState.Error   -> s.message
            is SolveState.Preview -> "Click to apply the previewed solution"
            else                  -> "Auto-solve the research grid"
        }

        ctx.drawTooltip(mouse, line)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * The button is logically disabled while [SolveState.Applying] is active
     * (the applier is writing to the server; there is nothing the user can do).
     */
    private fun enabled(): Boolean = controller.state !is SolveState.Applying

    private companion object {
        private val log = LogManager.getLogger(TcResearchSolverMod.MODID)
    }
}
