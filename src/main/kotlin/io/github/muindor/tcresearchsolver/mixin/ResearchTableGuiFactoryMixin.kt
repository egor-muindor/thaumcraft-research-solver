package io.github.muindor.tcresearchsolver.mixin

import elan.tweaks.common.gui.ComposableContainerGui
import elan.tweaks.common.gui.dto.Rectangle
import elan.tweaks.common.gui.dto.Scale
import elan.tweaks.common.gui.dto.Vector2D
import elan.tweaks.thaumcraft.research.frontend.integration.table.gui.ResearchTableGuiFactory
import io.github.muindor.tcresearchsolver.ui.GhostOverlayComponent
import io.github.muindor.tcresearchsolver.ui.HexPixelLayout
import io.github.muindor.tcresearchsolver.ui.LiveApplierPort
import io.github.muindor.tcresearchsolver.ui.SolveButtonUIComponent
import io.github.muindor.tcresearchsolver.ui.SolveController
import io.github.muindor.tcresearchsolver.ui.SolveWorker
import io.github.muindor.tcresearchsolver.ui.SpinnerComponent
import io.github.muindor.tcresearchsolver.ui.buildSnapshot
import net.minecraft.entity.player.EntityPlayer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import thaumcraft.common.tiles.TileResearchTable

/**
 * Appends the Solve UI components (button, spinner, ghost overlay) to the
 * [ComposableContainerGui] assembled by [ResearchTableGuiFactory.create].
 *
 * Injection strategy: RETURN-append — the factory builds the GUI and returns it;
 * we grab [CallbackInfoReturnable.getReturnValue], cast to [ComposableContainerGuiAccessor]
 * (a Mixin accessor that exposes the private final ArrayList fields), and add our components.
 * No redirect needed because the lists are mutable at runtime (ArrayList).
 *
 * Coordinates are first-cut placeholders; runClient (Phase 5) will tune placement.
 * Vector2D(Int, Int) constructor confirmed via `javap` on ThaumcraftResearchTweaks-1.3.0.jar.
 */
// remap = false: ResearchTableGuiFactory is an RT class (not vanilla MC). The MCP/Searge mapper
// has no entries for it, so the AP would error on the @Inject target method lookup.
// Disabling remap tells the AP to use method names/descriptors verbatim.
@Mixin(value = [ResearchTableGuiFactory::class], remap = false)
class ResearchTableGuiFactoryMixin {

    @Inject(
        method = ["create"],
        at = [At("RETURN")],
        remap = false,
    )
    private fun `tcresearchsolver$injectSolveUi`(
        player: EntityPlayer,
        tile: TileResearchTable,
        cir: CallbackInfoReturnable<ComposableContainerGui>,
    ) {
        val gui = cir.returnValue ?: return

        val worker = SolveWorker()
        val controller = SolveController(worker, LiveApplierPort(player, tile), previewConfirm = true)

        // First-cut placement — runClient (Phase 5) will tune these coordinates.
        // Vector2D(Int, Int) confirmed via javap on ThaumcraftResearchTweaks-1.3.0.jar.
        val buttonOrigin = Vector2D(8, 8)
        val button = SolveButtonUIComponent(
            bounds = Rectangle(buttonOrigin, Scale(50, 12)),
            controller = controller,
            worker = worker,
        ) { buildSnapshot(player, tile) }

        val spinner = SpinnerComponent(Vector2D(8, 22), controller)
        val ghost = GhostOverlayComponent(controller, HexPixelLayout::center, Vector2D(8, 36))

        val acc = gui as ComposableContainerGuiAccessor
        acc.tcrsGetBackgrounds().add(button)
        acc.tcrsGetMouseOverables().add(button)
        acc.tcrsGetClickables().add(button)
        acc.tcrsGetTickables().add(button)
        acc.tcrsGetTickables().add(spinner)
        acc.tcrsGetForegrounds().add(spinner)
        acc.tcrsGetForegrounds().add(ghost)
    }
}
