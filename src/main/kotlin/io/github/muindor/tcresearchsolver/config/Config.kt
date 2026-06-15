package io.github.muindor.tcresearchsolver.config

import net.minecraftforge.common.config.Configuration
import java.io.File

object Config {
    /** If true, Solve shows a ghost preview and waits for a second click to Apply; if false, auto-applies. */
    @Volatile var previewConfirm: Boolean = true
        private set

    /** Per-solve wall-clock cap in ms. 0 = use the solver's per-radius default budgets. */
    @Volatile var maxSolveMs: Int = 0
        private set

    fun load(file: File) {
        val cfg = Configuration(file)
        cfg.load()
        previewConfirm = cfg.getBoolean(
            "previewConfirm", Configuration.CATEGORY_GENERAL, true,
            "Show a ghost preview and require a second click to Apply (true), or auto-apply the solution (false).",
        )
        maxSolveMs = cfg.getInt(
            "maxSolveMs", Configuration.CATEGORY_GENERAL, 0, 0, 600_000,
            "Per-solve time budget in milliseconds. 0 = use the built-in per-radius defaults.",
        )
        if (cfg.hasChanged()) cfg.save()
    }
}
