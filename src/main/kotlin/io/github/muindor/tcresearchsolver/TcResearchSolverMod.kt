package io.github.muindor.tcresearchsolver

import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import org.apache.logging.log4j.LogManager

/**
 * Entry point. A Kotlin `object` driven by Forgelin's [net.shadowfacts.forgelin.KotlinAdapter]
 * (FML instantiates the singleton via its INSTANCE field) — the same pattern ThaumcraftResearchTweaks
 * uses. Dependency tokens: `forgelin` (Kotlin runtime), `spongemixins` (UniMixins coremod id),
 * `Thaumcraft`, and `ThaumcraftResearchTweaks` (its @Mod modid).
 */
@Mod(
    modid = TcResearchSolverMod.MODID,
    name = "Thaumcraft Research Solver",
    version = Tags.VERSION,
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
    dependencies =
        "required-after:forgelin;required-after:spongemixins;required-after:Thaumcraft;required-after:ThaumcraftResearchTweaks",
    acceptedMinecraftVersions = "[1.7.10]",
)
object TcResearchSolverMod {
    const val MODID = "tcresearchsolver"

    private val log = LogManager.getLogger(MODID)

    @Mod.EventHandler
    fun preInit(e: FMLPreInitializationEvent) {
        log.info("Thaumcraft Research Solver preInit (version {})", Tags.VERSION)
    }

    @Mod.EventHandler
    fun init(e: FMLInitializationEvent) {
        log.info("Thaumcraft Research Solver init")
    }
}
