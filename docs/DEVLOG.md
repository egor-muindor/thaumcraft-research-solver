# Dev log

## 2026-06-15 — Phase 1 (GTNH bootstrap) verified

Replaced the temporary plain-Kotlin build with the GTNH convention (mirrors
ThaumcraftResearchTweaks, the canonical Kotlin+Thaumcraft+mixins GTNH mod).

- Build: `./gradlew build` green on JDK 25; solver suite **120 tests, 0 failures**.
- Toolchain: gtnhgradle 2.0.24 (requires JVM >= 25; will NOT load on JDK 17).
  Spotless disabled because ktfmt crashes on JDK 25 — same as ResearchTweaks.
- Dependency coordinates resolved: `thaumcraft:Thaumcraft:1.7.10-4.2.3.5:dev`,
  `com.github.GTNewHorizons:thaumcraft-research-tweaks:1.3.0:dev`,
  `com.github.GTNewHorizons:Forgelin:2.0.3-GTNH`, UniMixins (`spongemixins`).
- Runtime smoke (real modpack, not runClient): deployed the reobf jar to the
  PrismLauncher `GT_New_Horizons_2.8.4_Java_17-25` instance. Game launches, mod
  appears in the FML mod list, research table opens the ResearchTweaks GUI, no
  crash / no missing-dependency error. (No UI buttons yet — that is Phase 4.)
