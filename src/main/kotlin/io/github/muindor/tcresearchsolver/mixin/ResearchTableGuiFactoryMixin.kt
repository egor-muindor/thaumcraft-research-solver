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
        val controller = SolveController(worker, LiveApplierPort(player, tile), previewConfirm = io.github.muindor.tcresearchsolver.config.Config.previewConfirm)

        // Placement in GUI-local coords (pinned to RT 1.3.0 ResearchTableInventoryTexture layout):
        //  - Button: top-center wood gap between UsageHint (ends x=135) and CopyButton (starts x=207),
        //    above the parchment (starts y=35). 50x12 fits the 72px gap.
        //  - Spinner/metadata: bottom gutter below the parchment (ends y=185), wide empty band.
        val buttonOrigin = Vector2D(146, 10)
        val button = SolveButtonUIComponent(
            bounds = Rectangle(buttonOrigin, Scale(50, 12)),
            controller = controller,
            worker = worker,
        ) { buildSnapshot(player, tile) }

        val spinner = SpinnerComponent(Vector2D(98, 186), controller)
        val ghost = GhostOverlayComponent(controller, HexPixelLayout::center, Vector2D(98, 186))

        // Spinner + ghost are BACKGROUND components (not foreground): RT's foreground pass is
        // double-offset (see GhostOverlayComponent). Appended after RT's components, so they draw
        // on top of the hex grid with the correct single (guiLeft,guiTop) offset.
        val acc = gui as ComposableContainerGuiAccessor
        acc.tcrsGetBackgrounds().add(button)
        acc.tcrsGetMouseOverables().add(button)
        acc.tcrsGetClickables().add(button)
        acc.tcrsGetTickables().add(button)
        acc.tcrsGetTickables().add(spinner)
        acc.tcrsGetBackgrounds().add(spinner)
        acc.tcrsGetBackgrounds().add(ghost)
    }
}
