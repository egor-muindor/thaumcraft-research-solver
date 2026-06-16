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

## 2026-06-16 — Phase 5.2 runClient session (IN FLIGHT — uncommitted on `feat/implementation`)

Live QA in the PrismLauncher GTNH instance surfaced four real integration bugs the 219 unit tests
(fakes) could not catch, plus UI placement work. All fixed in the working tree (not yet committed);
the deployed jar is `build/libs/tcresearchsolver-…-dirty.jar`. Investigations done by decompiling
RT 1.3.0 + TC 4.2.3.5 with Vineflower (`/tmp/vineflower.jar` → `/tmp/rtvf`, `/tmp/tcvf`).

**Bugs fixed (all verified live except where noted):**
- **Note source** (`ui/LiveWiring.kt` `buildSnapshot`): read the note from the **slot-1 ItemStack**
  via `ResearchManager.getData(tile.getStackInSlot(1))`, not `tile.data` (a server-only cache the
  tile never network-serializes → null/stale on client; RT itself reads slot-1).
- **Hex key format** (`integration/BoardReader.kt` `fromNoteData`): TC keys hexes by
  `Hex.toString()` = `"q:r"` (colon); solver/`toBoard` use `hexKey()` = `"q,r"` (comma). Passing
  colon keys made `toBoard` mark every cell Dead → VACANT cells never became Empty → `empties=0` →
  Solve silently no-op. Fix: re-key both maps through `hexKey(Hex(q,r))`. *(This was THE button
  "does nothing" root cause; the slot-1 change alone wasn't enough.)*
- **Solver `UNKNOWN_TIMEOUT` on radius-4+** (`ui/SolveWorker.kt`): the greedy feasible-first seed
  (`seedIncumbent`, unit-tested by `SolverSeedTest`) was disabled — `SolveOptions.seed` defaults
  false and the worker never set it. Without an incumbent, B&B pruning never engages and large
  boards find nothing. Fix: `seed = true`. R4 now returns OPTIMAL/FEASIBLE instead of nothing.
- **Ghost overlay** (`ui/LiveWiring.kt` `HexPixelLayout`, `ui/GhostOverlayComponent.kt`,
  `SpinnerComponent.kt`, mixin): (a) was reflecting RT's `ParchmentHexMapLayout.keyToHex` — wrong
  map (decorative runes, colon keys, parchment-center-relative, omits `"0:0"`). Replaced with RT's
  real formula `HexMath.toCenterVector(q,r,9) + centerUiOrigin(171,110)`, `origin = center-8`.
  (b) ghost+spinner were **Foreground** components → RT's foreground pass double-offsets
  (vanilla `glTranslate(guiLeft,guiTop)` + TableUIContext re-adds origin); moved to **Background**.
  (c) the "120" next to each ghost was our alpha passed as `drawTag`'s amount/count → switched to
  `drawTag(aspect, 0, 0, blend=771, alpha=0.5f, pos)` (translucent colored icon, no number).

**UI placement (GUI-local, pinned to RT `ResearchTableInventoryTexture`):** button `(8,8)` →
`(146,10)` (empty top-center gap between UsageHint x≤135 and CopyButton x≥207); spinner + metadata
→ `(98,186)` (bottom gutter below parchment).

**Checklist status:** #1 ✅ (after move). #2 ✅ button moved (awaiting visual confirm of (146,10)).
#3 ✅ formula fix shipped (awaiting visual confirm of alignment + window-resize). Solve/Preview/
Apply/Done pipeline ✅ verified on R2/R3/R4 live.

**STILL OPEN / NEXT SESSION:**
- Confirm ghost alignment on the hexes + at multiple window sizes (just shipped, not yet eyeballed).
- Confirm button at (146,10) doesn't collide with the texture; tune if needed.
- #4 Apply post-verify still optimistic (no deferred re-read of the note after server sync).
- #5/#6 negative paths (ink-missing, INFEASIBLE_INVENTORY) NOT tested. User reported the GUI
  "blocks" on these — needs reproduction + diagnosis (clarify what blocks).
- **Cleanup before finishing the branch:** remove the temporary `ui/Diag.kt` + all `Diag.trace`/
  `Diag.log` calls (tag `TCRS-DIAG`) in `SolveButtonUIComponent`, `LiveWiring.buildSnapshot`.
- Then commit this session's fixes, then **Task 5.3** (`./gradlew build test`; finish-branch skill).

Build: `./gradlew build` green on JDK 25; deploy the non-`-dev`/non-`-sources` reobf jar from
`build/libs/` to the GTNH `mods/` folder. See memory `phase5-runclient-findings` for the same.

## 2026-06-16 — Phase 5.3 (configurability + branch close-out)

Spec: `docs/superpowers/specs/2026-06-16-config-timeouts-fast-solve-design.md`. Built test-first
(RED→GREEN per unit); 231 tests green (was 219, +12).

**Configurability (the three requested knobs):**
- **Apply confirmation** — already existed as `previewConfirm` (default `true`); no change.
- **Per-radius solve timeouts** — new config `maxSolveMsR2..R5` (0 = built-in 5/10/20/30 s). The
  existing global `maxSolveMs` is now a **ceiling (`min`)**, not an override (a deliberate, approved
  semantics change — raising a budget above the built-in is now done via the per-radius keys).
  Precedence lives in the pure `resolveBudget(base, perRadiusMs, globalCapMs)` (`solver/Solver.kt`),
  wired into `LiveWiring.buildSnapshot`. Radius→key mapping is the pure `perRadiusMs(...)`
  (`config/Config.kt`), extracted for unit testing (Forge `Configuration` NPEs outside FML).
- **Fast solve** — new config `fastSolve` (default `false`) → `SolveSnapshot.fast` → `SolveWorker`
  → `SolveOptions.stopAtFirstFeasible`. In `solve()` a `stopEarly` flag returns the first feasible
  incumbent (skips DFS entirely when the seed already produced one; `nodes == 0`) and forces a
  non-exhaustive result, so fast mode reports `FEASIBLE_TIMEOUT`, never `OPTIMAL` (trivial
  0/1-anchor boards still short-circuit to `OPTIMAL` before search — correct).

**Tests added:** `solver/BudgetTest` (precedence), `solver/SolverFastModeTest` (early-exit +
seed-skip + infeasible-unchanged + default regression), `config/ConfigTest` (pure `perRadiusMs`).

**Cleanup:** removed temporary `ui/Diag.kt` + all 12 `TCRS-DIAG` call sites; the click handler now
logs snapshot-build failures via a real `LogManager` logger instead of swallowing them.

**Review:** Codex pre-release review — no correctness bugs (it confirmed the trivial-`OPTIMAL`
nuance and the intended `maxSolveMs` cap change; its `seed=true` revert suggestion was declined, as
that is the approved Phase 5.2 fix).

**Docs:** README now states tested-on **GTNH 2.8.4** + hard dependencies (Forgelin 2.0.3-GTNH,
UniMixins/`spongemixins`, Thaumcraft 4.2.3.5, Research Tweaks 1.3.0) + a Configuration section.

Shipped as the `v1.0.0-beta` pre-release for in-game verification before the stable release.
