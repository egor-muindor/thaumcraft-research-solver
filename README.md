# Thaumcraft Research Solver

A client-side GTNH 2.8.4 addon that adds a **"Solve"** button to the Thaumcraft research table:
it computes the optimal aspect layout for the current research note, previews it as ghosts, and on
confirmation auto-fills the hex grid by driving Thaumcraft's own network packets. Research
progression is preserved — you still need the note, the aspects, and the ink.

Kotlin (Forgelin), hexagonal architecture, in the style of (and depending on)
[ThaumcraftResearchTweaks](https://github.com/GTNewHorizons/thaumcraft-research-tweaks). No
Thaumcraft source is modified.

## Requirements

Built and tested against **GregTech: New Horizons (GTNH) 2.8.4** — every dependency below
ships with that pack, so no extra installation is needed there. Minecraft 1.7.10 /
Forge 10.13.4.1614, client side.

Hard dependencies (load order is enforced via the `@Mod` annotation):

- **Forgelin** `2.0.3-GTNH` — Kotlin language adapter / in-game runtime.
- **UniMixins** — the Mixin loader (this mod registers under the legacy `spongemixins` id).
- **Thaumcraft** `4.2.3.5` — the base mod whose research table is solved.
- **Thaumcraft Research Tweaks** `1.3.0` — the research-table GUI this addon hooks into.

## Configuration

On first launch the mod writes `config/tcresearchsolver.cfg`:

- `previewConfirm` (default `true`) — show a ghost preview and require a second click to
  Apply; set `false` to auto-apply.
- `maxSolveMsR2` / `maxSolveMsR3` / `maxSolveMsR4` / `maxSolveMsR5` (default `0`) — per-board
  solve time budget in ms; `0` keeps the built-in defaults (5 s / 10 s / 20 s / 30 s).
- `maxSolveMs` (default `0`) — a global ceiling applied on top of the per-radius budgets;
  `0` means no cap.
- `fastSolve` (default `false`) — stop at the first valid solution instead of the optimal
  one (much faster on large boards, but may use more or scarcer aspects).

## Status

Implemented and verified in-game on GTNH 2.8.4: Solve → ghost preview → Apply works on
radius-2 through radius-4 boards.

## Repo layout

- `docs/superpowers/specs/` — design spec(s).
- `reference/thaumcraft-integration.md` — Thaumcraft API/internals seams (verified via `javap`).
- `reference/researchtweaks-map.md` — the addon we hook into, and where the button/overlay go.
- `reference/ts-solver/` — the working browser solver, **read-only**, as the port source of truth.
- `reference/jars/` — TC + ResearchTweaks jars for local inspection (git-ignored, not committed).

## Origin

Ported from the standalone web solver `tcresearch-solver`. Same solver objective: lexicographic
`(scarcity → cells)`.
