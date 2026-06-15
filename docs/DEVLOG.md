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

## 2026-06-15 — Phase 3 (integration adapters) complete

The `integration/` layer that bridges live TC/RT state ↔ the pure solver. TDD
per file (pure logic unit-tested; TC/RT-touching code isolated below a clear
boundary, compiled against the deobf classpath, runClient-verified in Phase 5).

- **Task 3.1 — signatures pinned** (`javap`): recorded a CONFIRMED SIGNATURES
  section in `reference/{thaumcraft-integration,researchtweaks-map}.md`.
  Corrections vs prior guesses: `ResearchNoteData` is in `…lib.research` (the
  `…lib.utils.HexUtils` is the canonical one); packets in `…network.playerdata`;
  RT lives under `elan.tweaks.thaumcraft.research.frontend.…`. `HexEntry.type`
  ints: VACANT=0, ROOT=1, NODE=2.
- **3.2 AspectDataProvider** — `buildAspectDataFrom(registry entries)` mirrors
  the static `buildAspectData`; `fromLiveRegistry()` reads `Aspect.aspects`.
- **3.3 BoardReader** — `ResearchNoteData.hexes/hexEntries` → solver `Board`
  (ROOT→Anchor, NODE→Placed(locked), off-shape→Dead); radius from hex extent.
- **3.4 InventoryReader** — RT `AspectPool.totalAmountOf` (personal+bonus) per
  `data.universe`, gated by `hasDiscovered` → solver `Inventory`.
- **3.5 Applier** — pure `planApply` (direct-draw-first, deepest-first Combine
  emission, hexKey-ordered Place ops) + packet execution + `postVerify`.
- **Review (Opus + Codex):** Codex caught a HIGH bug — `apply` hard-coded the
  combine packet's `ab1/ab2=false`, but the server gate is
  `getAspectPoolFor(a) > 0 || abN` and draws bonus-pool components only when
  `abN` is set. Since the inventory uses `totalAmountOf` (incl. bonus), fixed to
  mirror RT's `AspectCombinerAdapter`: `abN = tile.bonusAspects.getAmount(a) > 0`
  (verified via `javap -c`). Added contention regression tests.
- Build: `./gradlew build` green on JDK 25; **169 tests, 0 failures**. Production
  reobf jar assembles. No runtime change yet (integration is dead code until the
  Phase 4 Mixin wires it), so no new PrismLauncher smoke this phase.
