# Thaumcraft Research Solver

A client-side GTNH 2.8.4 addon that adds a **"Solve"** button to the Thaumcraft research table:
it computes the optimal aspect layout for the current research note, previews it as ghosts, and on
confirmation auto-fills the hex grid by driving Thaumcraft's own network packets. Research
progression is preserved — you still need the note, the aspects, and the ink.

Kotlin (Forgelin), hexagonal architecture, in the style of (and depending on)
[ThaumcraftResearchTweaks](https://github.com/GTNewHorizons/thaumcraft-research-tweaks). No
Thaumcraft source is modified.

## Status

Pre-implementation. The approved design lives in
[`docs/superpowers/specs/2026-06-15-thaumcraft-research-solver-design.md`](docs/superpowers/specs/2026-06-15-thaumcraft-research-solver-design.md).
Build scaffolding and code come next, driven by the implementation plan.

## Repo layout

- `docs/superpowers/specs/` — design spec(s).
- `reference/thaumcraft-integration.md` — Thaumcraft API/internals seams (verified via `javap`).
- `reference/researchtweaks-map.md` — the addon we hook into, and where the button/overlay go.
- `reference/ts-solver/` — the working browser solver, **read-only**, as the port source of truth.
- `reference/jars/` — TC + ResearchTweaks jars for local inspection (git-ignored, not committed).

## Origin

Ported from the standalone web solver `tcresearch-solver`. Same solver objective: lexicographic
`(scarcity → cells)`.
