package io.github.muindor.tcresearchsolver.mixin

import elan.tweaks.common.gui.ComposableContainerGui
import elan.tweaks.common.gui.component.BackgroundUIComponent
import elan.tweaks.common.gui.component.ClickableUIComponent
import elan.tweaks.common.gui.component.ForegroundUIComponent
import elan.tweaks.common.gui.component.MouseOverUIComponent
import elan.tweaks.common.gui.component.TickingUIComponent
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

/**
 * Exposes the private final List fields of [ComposableContainerGui] so that
 * [ResearchTableGuiFactoryMixin] can append our UI components at RETURN time.
 *
 * Field names confirmed via `javap -p` on ThaumcraftResearchTweaks-1.3.0.jar:
 *   private final List<BackgroundUIComponent>  backgrounds
 *   private final List<ForegroundUIComponent>  foregrounds
 *   private final List<TickingUIComponent>     tickables
 *   private final List<MouseOverUIComponent>   mouseOverables
 *   private final List<ClickableUIComponent>   clickables
 *
 * All lists are runtime ArrayList instances (verified in the ctor bytecode) so
 * casting the returned List to MutableList and calling .add() is safe.
 *
 * The accessor return type uses `MutableList` (Kotlin alias for java.util.List with
 * mutation methods exposed); the Mixin AP accepts this because ArrayList satisfies both.
 */
// remap = false: ComposableContainerGui is an RT class; the MCP/Searge mapper has no entries
// for it, so the AP would error (or warn) on every @Accessor target. Disabling remap tells the
// AP to use the field names verbatim without consulting the obfuscation SRG.
@Mixin(value = [ComposableContainerGui::class], remap = false)
interface ComposableContainerGuiAccessor {

    @Accessor("backgrounds")
    fun tcrsGetBackgrounds(): MutableList<BackgroundUIComponent>

    @Accessor("foregrounds")
    fun tcrsGetForegrounds(): MutableList<ForegroundUIComponent>

    @Accessor("tickables")
    fun tcrsGetTickables(): MutableList<TickingUIComponent>

    @Accessor("mouseOverables")
    fun tcrsGetMouseOverables(): MutableList<MouseOverUIComponent>

    @Accessor("clickables")
    fun tcrsGetClickables(): MutableList<ClickableUIComponent>
}
