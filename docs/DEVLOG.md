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

## 2026-06-16 — Phase 4 (UI: Mixin + state machine + components) implemented

The `ui` layer + the GUI Mixin. Pure logic is unit-tested; RT/MC-touching code is
compile-verified and **runtime-validated at runClient (Phase 5.2 — pending)**.

- **4.1 — GUI seam pinned** (`javap`): recorded in `reference/researchtweaks-map.md`
  "CONFIRMED GUI SEAM". Key facts: `ResearchTableGuiFactory.create(player, tile)` →
  `ComposableContainerGui` whose component lists are **mutable ArrayLists**; inject at
  RETURN + append via an `@Accessor`. `TileResearchTable.data` is a public
  `ResearchNoteData` (read the note directly — no Container needed). RT modid =
  `ThaumcraftResearchTweaks`; coremod = `spongemixins`.
- **4.2 SolveController** — pure state machine (Idle/Solving/Preview/Applying/Done/Error),
  injected worker+applier ports, `previewConfirm` auto-apply. 43→44 tests.
- **4.3 SolveWorker** — off-thread `solveWithValidation`; client-thread `pump()` delivers
  progress/result (worker thread touches only atomics). Cancel→CANCELLED.
- **4.4–4.6** — `SolveButtonUIComponent` (mirrors CopyButton; label/click by state, pumps
  in onTick), `SpinnerComponent`, `GhostOverlayComponent` (translucent `drawTag` ghosts via
  a hex→pixel lookup). Text-label rendering first cut (texture polish deferred).
- **4.7 Mixin + glue** — `ResearchTableGuiFactoryMixin` (`@Inject(RETURN)`, `remap=false`,
  appends the 3 components via `ComposableContainerGuiAccessor`), `ui/LiveWiring.kt`
  (`buildSnapshot` from `tile.data`, `LiveApplierPort`, reflective `ParchmentHexMapLayout`
  hex→pixel). `mixins.tcresearchsolver.json` client list registered.
- **Review (Opus + Codex):** Codex caught a HIGH cancel-then-restart race (a superseded
  solve could splice a stale-snapshot result into a new run) → fixed with a generation
  counter; and a MED GUI-close leak (solve ran to budget after close) → fixed with a
  pump heartbeat / orphan-timeout self-cancel. (LOW: spinner is frame-rate-paced — deferred.)
- Build: `./gradlew build` green (mixin AP + refmap + reobf) on JDK 25; **219 tests, 0
  failures**. Production jar `tcresearchsolver-<ver>.jar` deployed to the PrismLauncher
  `GT_New_Horizons_2.8.4_Java_17-25` instance for runClient verification.

### runClient verify checklist (Phase 5.2) — open items flagged during Phase 4
1. The Mixin actually applies (Solve button appears in the RT research-table GUI).
2. Button/spinner/ghost placement coords (first-cut `Vector2D(8,8/22/36)`) — tune to the GUI.
3. Ghost alignment: `ParchmentHexMapLayout.keyToHex` key format == solver `"q,r"`, and
   `getCenter()` aligns at multiple window sizes.
4. Apply path: combine-before-place ordering accepted by the server; post-verify (currently
   optimistic — needs a deferred re-read of `tile.data` after server sync).
5. Ink-missing → Apply reports "scribing tools missing or empty" (abortReason → Error state).
6. INFEASIBLE_INVENTORY path surfaces a useful message and returns to a usable state.
