# Design: configurable per-size timeouts + fast-solve

Date: 2026-06-16
Status: approved (brainstorm), implementing
Branch: feat/implementation

## Goal

Add three configurability knobs requested for the first release. One already
existed; this work adds the other two and cleans up temporary diagnostics.

1. **Apply confirmation** — already implemented as `Config.previewConfirm`
   (default `true`: ghost preview + second click to Apply). **No change.**
2. **Per-size solve timeouts** — make the built-in per-radius time budgets
   (R2=5s, R3=10s, R4=20s, R5=30s) configurable, keeping the existing global
   `maxSolveMs` as a ceiling.
3. **Fast solve** — option to stop the search at the first valid solution
   instead of proving optimality (faster, lower quality). Default off.

## Decisions (confirmed with user)

- **Global cap = `min`** (a true ceiling), not "override-wins".
- **Fast result reuses `FEASIBLE_TIMEOUT`** (its meaning is exactly "feasible,
  optimality not proven"). The UI already routes `OPTIMAL` and
  `FEASIBLE_TIMEOUT` identically, so no UI change and no new enum value.

## Changes

### Config (`config/Config.kt`)
Add to `CATEGORY_GENERAL`:
- `maxSolveMsR2 / R3 / R4 / R5`: `Int`, default `0`, range `0..600_000`.
  `0` = use the solver's built-in default for that radius.
- `fastSolve`: `Boolean`, default `false`.
- helper `maxSolveMsForRadius(radius): Int` mapping 2/3/4/5 to the right field,
  else `0`.

### Budget precedence (`solver/Solver.kt` — new pure helper)
`resolveBudget(base, perRadiusMs, globalCapMs): SolveBudget`
```
sizeMs      = perRadiusMs > 0 ? perRadiusMs : base.maxTimeMs
effectiveMs = globalCapMs > 0 ? min(globalCapMs, sizeMs) : sizeMs
            // nodes & beam unchanged
```
Pure → unit-testable without Forge. `LiveWiring.buildSnapshot` calls it with
`budgetForRadius(radius)`, `Config.maxSolveMsForRadius(radius)`, `Config.maxSolveMs`.

### Fast solve (`solver/Solver.kt`)
- `SolveOptions.stopAtFirstFeasible: Boolean = false`.
- In `solve()`: a `stopEarly` flag.
  - The existing `seed=true` greedy pass already yields a feasible incumbent on
    success → when fast + seed found one, **skip `dfs()` entirely** (`nodes`
    stays 0).
  - Otherwise run DFS but bail the moment `onComplete()` records the first
    feasible incumbent (mirror the existing `cancelled` checks at the top of
    `dfs()`, after the goal check, and after each include-branch recursion).
  - `exhaustive = !truncated && !cancelled && !stopEarly` → an early-exit run
    reports `FEASIBLE_TIMEOUT`, never `OPTIMAL`.
- Trivial 0/1-anchor boards still return `OPTIMAL` via the pre-search early-return
  (nothing to place, so the result genuinely is optimal); fast mode only affects
  the search path, never this case.
- Infeasible boards are unaffected (nothing to early-exit on → still
  `INFEASIBLE_INVENTORY` / `UNSAT_PROVEN` / `UNKNOWN_TIMEOUT`).

### Wiring
- `SolveSnapshot.fast: Boolean = false` (new field, default keeps existing
  constructors working).
- `LiveWiring.buildSnapshot` sets `fast = Config.fastSolve`.
- `SolveWorker` passes `stopAtFirstFeasible = snapshot.fast` into `SolveOptions`.

### Cleanup (branch close-out)
- Delete `ui/Diag.kt` and all 12 `TCRS-DIAG` call sites in
  `SolveButtonUIComponent.kt` and `LiveWiring.kt`.

## Testing (TDD)
- `solver/BudgetTest.kt` — `resolveBudget` precedence: per-radius override,
  built-in fallback, global cap as `min`, cap below/above size.
- `solver/SolverFastModeTest.kt` —
  - seed off + fast: same exhaustible board returns `OPTIMAL` without fast and
    `FEASIBLE_TIMEOUT` (valid board, `nodes <= full`) with fast.
  - seed on + fast: `nodes == 0` (DFS skipped), valid board, `FEASIBLE_TIMEOUT`.
  - fast off: unchanged `OPTIMAL` optimum (regression).
- `config/ConfigTest.kt` — load round-trip: defaults, and `maxSolveMsForRadius`
  maps each radius to its own key (catches mapping typos). Forge `Configuration`
  is a plain file parser, usable in unit tests.

## Out of scope (user deprioritized — "everything works")
Negative-path repro (#5/#6), ghost visual re-confirm, Apply post-verify.

## Ship
After implementation + green tests: hand user the reobf jar to verify live, then
commit, push, GitHub release.
