# TS solver — port source (reference only, do NOT edit)

Verbatim copy of the working browser solver from the `tcresearch-solver` project. This is the
**source of truth** to port to Kotlin. Keep it read-only; if behaviour questions arise, this is the
oracle. The Kotlin port should reproduce these algorithms file-for-file and match their outputs.

## What to port (pure logic, no browser/DOM deps)

| TS file (`core/`)  | role                                                            |
|--------------------|-----------------------------------------------------------------|
| `hex.ts`           | axial coords, neighbours, distance, board cells                 |
| `cost.ts`          | lexicographic cost `(scarcity, cells)` + comparator             |
| `aspectGraph.ts`   | adjacency (`isValidLink`), combinations (`mult`), `primalVec`   |
| `inventory.ts`     | `Inventory` supply+threshold, `directPenalty`, `obtainCost`, allocate |
| `heuristic.ts`     | admissible remainder heuristic                                  |
| `board.ts`         | board model, anchors, validation, filled-neighbour queries      |
| `steiner.ts`       | Steiner-tree seed for the anytime search                        |
| `solver.ts`        | branch-and-bound w/ `onProgress`/`shouldCancel`, per-radius budgets |
| `data/aspects.ts`  | `AspectData` shape; in-game this is built live from `Aspect.aspects` |
| `data/raw.ts`      | static aspect table (web). In-game, prefer the live TC registry.|

## Objective (locked for this addon)

Lexicographic `compareCost`: minimise `scarcity` first (consumption of aspects you are short of,
relative to a threshold; compounds cost = recursive craft cost from components; `+Inf` if an aspect
is unobtainable), then minimise `cells` (number of hexes written). See `cost.ts` + `inventory.ts`.

## Cross-check tests

`tests/core/*` + `tests/data/aspects.test.ts` are the behavioural spec. Port representative cases to
JUnit, and add "golden" cases: feed an identical board+inventory to the TS solver and the Kotlin
port and assert equal `(status, cost, cells)`.
