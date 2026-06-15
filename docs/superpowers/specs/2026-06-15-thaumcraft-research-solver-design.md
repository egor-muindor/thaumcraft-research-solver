# Thaumcraft Research Solver — design

**Date:** 2026-06-15
**Status:** approved design, pre-implementation
**Project:** `~/projects/thaumcraft-research-solver` (modid TBD: `tcresearchsolver`)

## Goal

A GTNH 2.8.4 client addon that adds a **"Solve" button** to the in-game Thaumcraft research table.
Pressing it computes the optimal aspect layout for the current research note and, after a preview
step, auto-fills the hex grid by driving the same network packets the player would. Research
**progression is preserved**: the player still needs the note, the aspects, and the scribing ink;
the addon only performs the puzzle for them. No Thaumcraft source is modified.

Feasibility was verified by disassembly (see `reference/thaumcraft-integration.md` and
`reference/researchtweaks-map.md`): all required state and actions are reachable from public API +
accessible internals, and GTNH already ships `ThaumcraftResearchTweaks`, a pure addon that replaces
the GUI and manipulates notes the same way — proof and reference for every seam.

## Constraints & decisions (locked)

- **Packaging:** separate Kotlin mod, GTNH style (UniMixins + Forgelin + `ForceLoadAsMod`).
- **Hook:** a Mixin injects our components into ResearchTweaks' `ComposableContainerGui` (which
  replaces the vanilla GUI in GTNH). Hard-depends on Thaumcraft + ThaumcraftResearchTweaks +
  UniMixins.
- **Solver:** port the existing TS solver to Kotlin, unchanged objective: lexicographic
  `(scarcity → cells)`, fed by the live aspect pool. Source in `reference/ts-solver/`.
- **UX:** two-step. Click → background solve (animated progress, cancelable, up to ~30 s on big
  boards) → ghost preview → "Apply" → auto-combine missing compounds + place via packets.
- **GUI alignment (Variant 1):** reuse ResearchTweaks' layout (`ParchmentHexMapLayout`/`HexLayout`)
  for hex→pixel so button/ghosts stay aligned in the resizable GUI.

## Architecture — three layers (hexagonal, like ResearchTweaks)

### 1. `solver` — pure core (no Minecraft deps)

Direct Kotlin port of `reference/ts-solver/core/*` (file-for-file): `Hex`, `Cost`, `AspectGraph`,
`Inventory`, `Heuristic`, `Board`, `Steiner`, `Solver`. Input: anchors + grid shape + locked cells
+ `Inventory`. Output: `SolveResult { status, placements: List<(hexKey, Aspect)>, cost }`. Emits
progress via `onProgress(Progress)` and honors `shouldCancel()`. Unit-tested on the JVM (JUnit),
with golden cross-checks against the TS oracle.

`SolverStatus` (ported): `OPTIMAL`, `FEASIBLE_TIMEOUT`, `UNKNOWN_TIMEOUT`,
`INFEASIBLE_INVENTORY`, `UNSAT_PROVEN`, `CANCELLED`, `INVALID_INPUT`.

### 2. `integration` — adapters (bridge to TC), mirroring ResearchTweaks ports

- `AspectDataProvider`: build `AspectGraph` (adjacency + combinations + primals) **live** from
  `Aspect.aspects` / `getComponents()` so all GTNH mod aspects are covered automatically.
- `BoardReader`: from `ResearchManager.getData(note)` → anchors (`hexEntries` type `ROOT`), grid
  shape (`hexes`), already-written cells (type `NODE`).
- `InventoryReader`: from `AspectPool.amountOf/bonusAmountOf/hasDiscovered` → solver `Inventory`
  (reuse or mirror `AspectPoolAdapter`).
- `Applier`: execute a solution — `combine` missing compounds, then `PacketAspectPlaceToServer`
  per cell via `PacketHandler.INSTANCE`; gate on `ScribeTools.areMissingOrEmpty()`; post-verify.

### 3. `ui` — Mixin + GUI components

- `SolveButtonUIComponent` (copy `CopyButtonUIComponent` pattern), a progress `SpinnerComponent`,
  and a `GhostOverlayComponent` (`ForegroundUIComponent`) positioned via ResearchTweaks' layout.
- A Mixin on `ResearchTableGuiFactory.create(...)` (or the GUI ctor) appends these components and
  captures references to the layout + `TileResearchTable` + `Container` + player.
- Optional keybind duplicates the button; optional Forge config (see below).

## State machine (button)

`Idle → Solving → Preview → Applying → Done`, plus `Error`.

- **Idle:** enabled iff a valid, incomplete, non-corrupted note is present.
- **Solving:** snapshot inputs on the client thread → hand to a single background worker thread →
  spinner + `⏱ {s}s · nodes {n} · best {cells}` + **Cancel** (sets cancel flag). Worker touches no
  Minecraft state.
- **Preview:** ghosts drawn on the solution cells; button becomes **Apply** (+ **Reset**). Result
  metadata shown (e.g. "optimal" vs "best found, not proven optimal" on timeout).
- **Applying:** on the client thread, re-check ink + pool; send combination packets, then placement
  packets, in order; the **server** is authoritative and processes sequentially.
- **Done / Error:** post-verify by re-reading the note; report rejected cells with reason
  (out of ink / missing aspect). On `INFEASIBLE_INVENTORY` / `UNKNOWN_TIMEOUT`, explain what's
  missing and stay in Idle.

## Concurrency model

The solve runs off-thread to avoid freezing the client (budgets: R2 ≈ 5 s … R5 ≈ 30 s). Only pure
computation runs on the worker; all Minecraft reads happen in a pre-snapshot on the client thread,
and all packet sends happen on the client thread during Apply. Progress is shared via atomics and
polled each render frame. Cancellation via an `AtomicBoolean` checked by `shouldCancel()`.

## Apply correctness

The note is never written locally; we only send packets and let the server sync the note back
(no client/server desync — same model the manual GUI uses). Combination packets precede the
placement packets that depend on them; the server applies them sequentially against its
authoritative pool, so dependencies hold. A short post-verify re-reads `ResearchManager.getData`
to confirm and surface any server rejection.

## Build & project setup

GTNH-style Gradle (RetroFuturaGradle + GTNH buildscript), Kotlin via Forgelin, UniMixins for the
mixin. `mixins.<modid>.json` (compatibilityLevel `JAVA_8`, refmap) targets the ResearchTweaks GUI
factory. `mcmod.info` deps: `Thaumcraft`, `ThaumcraftResearchTweaks`, `Forgelin`, `SpongeMixins`.
Deobf compile deps for TC + ResearchTweaks pinned from GTNH maven (coordinates fixed in the plan).
The Gradle/Kotlin scaffolding is **step 1 of the implementation plan**, not part of this design.

**Forge config:** max solve time (ms), keybind toggle, and a `previewConfirm` flag
(two-step default; one-click optional).

## Testing

- **Solver core:** JUnit unit tests ported from `reference/ts-solver/tests/core/*` + golden
  cross-checks vs the TS oracle (identical board+inventory → identical `status/cost/cells`).
- **Adapters:** targeted tests on mocked aspect data where feasible.
- **Integration/UI:** manual verification in the dev client (`runClient`): open a research table,
  Solve a known note, confirm the grid auto-fills and the research completes; verify ink/aspect
  shortage paths show correct messages.

## Risks & mitigations

1. **Mixin coupling to ResearchTweaks internals** — read the upstream source, pin to the shipped
   1.3.0; keep the injection surface minimal (append components + capture refs).
2. **Apply ordering vs server pool** — sequential packet sends + post-verify; trust the
   authoritative server (same as manual play).
3. **JVM solver performance / budgets** — re-bench on the JVM and re-tune the per-radius budgets.
4. **Aspect-graph completeness across mods** — build adjacency/combinations live from the registry.
5. **`HexEntry.type` exact int values** — confirm in dev before relying on ROOT/NODE/VACANT.

## Out of scope (YAGNI)

- Supporting the vanilla `GuiResearchTable` (GTNH always ships ResearchTweaks).
- Server-side mod component (client-only; server stays vanilla TC).
- Solving anything beyond the note hex minigame (no scanning/aspect-discovery automation).
