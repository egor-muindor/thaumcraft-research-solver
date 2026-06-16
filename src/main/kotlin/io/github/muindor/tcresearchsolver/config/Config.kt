package io.github.muindor.tcresearchsolver.config

import net.minecraftforge.common.config.Configuration
import java.io.File

/** Pure radius→timeout selector (extracted for testability; see ConfigTest). 0 = no override. */
internal fun perRadiusMs(radius: Int, r2: Int, r3: Int, r4: Int, r5: Int): Int = when (radius) {
    2 -> r2
    3 -> r3
    4 -> r4
    5 -> r5
    else -> 0
}

object Config {
    /** If true, Solve shows a ghost preview and waits for a second click to Apply; if false, auto-applies. */
    @Volatile var previewConfirm: Boolean = true
        private set

    /** Per-solve wall-clock cap in ms. 0 = use the solver's per-radius default budgets. */
    @Volatile var maxSolveMs: Int = 0
        private set

    /** Per-radius solve time budgets in ms. 0 = use the solver's built-in default for that radius. */
    @Volatile var maxSolveMsR2: Int = 0
        private set
    @Volatile var maxSolveMsR3: Int = 0
        private set
    @Volatile var maxSolveMsR4: Int = 0
        private set
    @Volatile var maxSolveMsR5: Int = 0
        private set

    /** If true, stop the search at the first valid solution (faster, lower quality). */
    @Volatile var fastSolve: Boolean = false
        private set

    /** The per-radius timeout override for [radius] (0 = none / unknown radius). */
    fun maxSolveMsForRadius(radius: Int): Int =
        perRadiusMs(radius, maxSolveMsR2, maxSolveMsR3, maxSolveMsR4, maxSolveMsR5)

    fun load(file: File) {
        val cfg = Configuration(file)
        cfg.load()
        previewConfirm = cfg.getBoolean(
            "previewConfirm", Configuration.CATEGORY_GENERAL, true,
            "Show a ghost preview and require a second click to Apply (true), or auto-apply the solution (false).",
        )
        maxSolveMs = cfg.getInt(
            "maxSolveMs", Configuration.CATEGORY_GENERAL, 0, 0, 600_000,
            "Global per-solve time cap in milliseconds (a ceiling over the per-radius budgets). " +
                "0 = no cap.",
        )
        maxSolveMsR2 = cfg.getInt(
            "maxSolveMsR2", Configuration.CATEGORY_GENERAL, 0, 0, 600_000,
            "Time budget in ms for radius-2 boards. 0 = built-in default (5000).",
        )
        maxSolveMsR3 = cfg.getInt(
            "maxSolveMsR3", Configuration.CATEGORY_GENERAL, 0, 0, 600_000,
            "Time budget in ms for radius-3 boards. 0 = built-in default (10000).",
        )
        maxSolveMsR4 = cfg.getInt(
            "maxSolveMsR4", Configuration.CATEGORY_GENERAL, 0, 0, 600_000,
            "Time budget in ms for radius-4 boards. 0 = built-in default (20000).",
        )
        maxSolveMsR5 = cfg.getInt(
            "maxSolveMsR5", Configuration.CATEGORY_GENERAL, 0, 0, 600_000,
            "Time budget in ms for radius-5 boards. 0 = built-in default (30000).",
        )
        fastSolve = cfg.getBoolean(
            "fastSolve", Configuration.CATEGORY_GENERAL, false,
            "Stop at the first valid solution instead of searching for the optimal one " +
                "(much faster on large boards, but may use more/scarcer aspects).",
        )
        if (cfg.hasChanged()) cfg.save()
    }
}
