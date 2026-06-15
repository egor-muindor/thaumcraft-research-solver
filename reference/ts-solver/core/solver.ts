import type { AspectData, Aspect } from '../data/aspects';
import { type Inventory, obtainCost, allocate, type AllocationResult, type AllocBudget, validateInventory } from './inventory';
import { type Board, createBoard, getState, setState, filledCells, filledNeighbors, allAnchorsConnected, anchorCells, validate, type ValidationError } from './board';
import { type Hex, hexKey, parseHexKey, neighborsOf, boardCells, isOnBoard } from './hex';
import { isValidLink } from './aspectGraph';
import { type Cost, addCost, compareCost, lessThan, ZERO_COST } from './cost';
import { remainderHeuristic } from './heuristic';

export type SolverStatus =
  | 'OPTIMAL' | 'FEASIBLE_TIMEOUT' | 'UNKNOWN_TIMEOUT'
  | 'INFEASIBLE_INVENTORY' | 'UNSAT_PROVEN' | 'CANCELLED' | 'INVALID_INPUT';

export const MAX_ANCHORS = 8;

export interface SolveBudget { maxNodes: number; maxTimeMs: number; beam?: number; }
export interface Progress { nodes: number; best: Cost | null; timeMs: number; status: 'searching' | 'seeding' | 'beam'; }

/**
 * Explicit per-radius budgets (spec §5.5). Starting points — TUNE against the R2–R5 bench (Task 6.6)
 * and freeze the measured values. `beam` caps include-branch aspect fan-out on heavy boards and forces
 * a *_TIMEOUT status (never *_PROVEN). Memory is bounded indirectly by maxNodes (DFS depth <= cells).
 */
export const DEFAULT_BUDGETS: Record<2 | 3 | 4 | 5, SolveBudget> = {
  2: { maxNodes: 500_000, maxTimeMs: 5_000 },
  3: { maxNodes: 2_000_000, maxTimeMs: 10_000 },
  4: { maxNodes: 4_000_000, maxTimeMs: 20_000, beam: 12 },
  5: { maxNodes: 6_000_000, maxTimeMs: 30_000, beam: 8 },
};

export function budgetForRadius(radius: number): SolveBudget {
  return DEFAULT_BUDGETS[(radius as 2 | 3 | 4 | 5)] ?? DEFAULT_BUDGETS[5];
}

export interface SolveOptions {
  data: AspectData;
  board: Board;            // initial anchors + locked (pre-validated by caller)
  inventory: Inventory;
  budget: SolveBudget;
  allocBudget?: AllocBudget;
  seed?: boolean;                // enable the optional anytime seed (Task 6.7); default off
  onProgress?: (p: Progress) => void;
  shouldCancel?: () => boolean; // best-effort cooperative cancel (worker uses hard terminate)
  now?: () => number;            // injectable clock for tests (defaults to Date.now via caller/worker)
}

export interface SolveResult {
  status: SolverStatus;
  board?: Board;
  cost?: Cost;
  allocation?: AllocationResult;
  stats: { nodes: number; timeMs: number };
}

export interface SolveWithValidationResult extends SolveResult {
  errors?: ValidationError[];
  message?: string;
}

export function solveWithValidation(opts: SolveOptions): SolveWithValidationResult {
  // Inventory pre-validation (spec §4.1): reject negative/non-integer supply or threshold<=0
  // up front instead of letting it surface as a worker error mid-search.
  try {
    validateInventory(opts.inventory);
  } catch (err) {
    return { status: 'INVALID_INPUT', message: err instanceof Error ? err.message : String(err), stats: { nodes: 0, timeMs: 0 } };
  }
  // Anchor cap (spec §5.1): core boundary enforces max 8 anchors.
  const anchorCount = anchorCells(opts.board).length;
  if (anchorCount > MAX_ANCHORS) {
    return { status: 'INVALID_INPUT', message: `too many anchors: ${anchorCount} (max ${MAX_ANCHORS})`, stats: { nodes: 0, timeMs: 0 } };
  }
  const v = validate(opts.data, opts.board);
  const unfixable = v.errors.filter((e) => e.type !== 'ANCHORS_DISCONNECTED');
  if (unfixable.length > 0) {
    return { status: 'INVALID_INPUT', errors: unfixable, stats: { nodes: 0, timeMs: 0 } };
  }
  return solve(opts);
}

interface Placement { key: string; aspect: Aspect; }

export function solve(opts: SolveOptions): SolveResult {
  const { data, board: initial, inventory, budget } = opts;
  const allocBudget = opts.allocBudget ?? { maxNodes: 200_000 };
  const now = opts.now ?? (() => Date.now()); // tests may inject deterministic clocks; default is real time
  const start = now();
  const anchors = anchorCells(initial);

  // 0/1 anchor => trivially solved (spec §5.1)
  if (anchors.length <= 1) {
    return { status: 'OPTIMAL', board: cloneBoard(initial), cost: ZERO_COST, stats: { nodes: 0, timeMs: 0 } };
  }

  // Unfixable initial invalidity: link errors among fixed (anchor/locked) cells cannot be repaired by
  // filling EMPTY cells, so no valid board exists. ANCHORS_DISCONNECTED is fixable (solver connects them) => ignored.
  const initialErrors = validate(data, initial).errors.filter((e) => e.type !== 'ANCHORS_DISCONNECTED');
  if (initialErrors.length > 0) {
    return { status: 'UNSAT_PROVEN', stats: { nodes: 0, timeMs: 0 } };
  }

  // Pre-compute anchor hex keys for fast connectivity check.
  const anchorKeys: string[] = anchors.map((a) => hexKey(a.hex));

  // working board mutated during DFS; placements stack of solver cells; excluded = frontier cells
  // decided to remain EMPTY in the current subtree (the include/exclude enumeration is non-redundant).
  const work = cloneBoard(initial);
  const placements: Placement[] = [];
  const excluded = new Set<string>();

  // Fast anchor connectivity check using work.cells directly (avoids boardCells scan).
  const fastAnchorsConnected = (): boolean => {
    const startKey = anchorKeys[0]!;
    const seen = new Set<string>([startKey]);
    const queue = [parseHexKey(startKey)];
    while (queue.length) {
      const cur = queue.pop()!;
      for (const n of neighborsOf(cur)) {
        const nk = hexKey(n);
        if (seen.has(nk)) continue;
        const ns = work.cells.get(nk);
        if (!ns || ns.kind === 'EMPTY' || ns.kind === 'DEAD') continue;
        seen.add(nk);
        queue.push(n);
      }
    }
    for (const ak of anchorKeys) if (!seen.has(ak)) return false;
    return true;
  };

  let incumbent: { board: Board; cost: Cost; alloc: AllocationResult } | null = null;
  let anyValidBoardFound = false;
  let anyUnknownCompetitive = false;
  let nodes = 0;
  let cancelled = false;
  let truncated = false; // budget/beam hit before exhaustion

  const placedCost = (): Cost => {
    let scarcity = 0;
    for (const p of placements) scarcity += obtainCost(inventory, data, p.aspect);
    return { scarcity, cells: placements.length };
  };

  // the one frontier cell to decide next = lowest-hexKey EMPTY cell adjacent to the structure
  // that is not already excluded; null => every frontier cell decided (leaf).
  // Iterates work.cells directly (only non-EMPTY cells stored) to avoid scanning all board cells.
  const nextUndecidedFrontierCell = (): Hex | null => {
    let best: Hex | null = null;
    let bestK = '';
    const seen = new Set<string>();
    for (const [ck, cs] of work.cells) {
      if (cs.kind === 'DEAD') continue; // DEAD cells have no placed aspect
      const ch = parseHexKey(ck);
      for (const n of neighborsOf(ch)) {
        if (!isOnBoard(n, work.radius)) continue;
        const nk = hexKey(n);
        if (seen.has(nk)) continue;
        seen.add(nk);
        if (excluded.has(nk)) continue;
        if ((work.cells.get(nk)?.kind ?? 'EMPTY') !== 'EMPTY') continue;
        if (best === null || nk < bestK) { best = n; bestK = nk; }
      }
    }
    return best;
  };

  const reportProgress = (): void => {
    opts.onProgress?.({
      nodes,
      best: incumbent ? incumbent.cost : null,
      timeMs: now() - start,
      status: 'searching',
    });
  };

  const validPlacement = (h: Hex, a: Aspect): boolean => {
    for (const n of neighborsOf(h)) {
      if (!isOnBoard(n, work.radius)) continue;
      const ns = work.cells.get(hexKey(n));
      if (!ns) continue; // EMPTY (absent from map)
      if (ns.kind === 'DEAD' || ns.kind === 'EMPTY') continue;
      const na = ns.kind === 'ANCHOR' ? ns.aspect : ns.aspect;
      if (na === a) return false;
      if (!isValidLink(data, na, a)) return false;
    }
    return true;
  };

  const onComplete = (): void => {
    anyValidBoardFound = true; // it's link-valid (placements were validated incrementally) and connected
    const gcost = placedCost();
    const alloc = allocate(inventory, data, demandOf(placements), allocBudget);
    if (alloc.feasible === true) {
      const cost: Cost = { scarcity: alloc.scarcityCost, cells: placements.length };
      if (!incumbent || lessThan(cost, incumbent.cost)) {
        incumbent = { board: cloneBoard(work), cost, alloc };
      }
    } else if (alloc.feasible === 'unknown') {
      // Allocator budget exhausted for this board: feasibility unproven. Record that a competitive
      // unknown exists — this BLOCKS proof statuses (OPTIMAL/INFEASIBLE_INVENTORY degrade to *_TIMEOUT),
      // but DO NOT stop the search: continuing may still find a feasible incumbent (anytime, invariant 3).
      if (!incumbent || lessThan(gcost, incumbent.cost)) {
        anyUnknownCompetitive = true;
      }
    } // feasible === false => discard entirely
  };

  // DFS with include/exclude branching (complete); periodic cancel/budget/progress checks.
  const dfs = (): void => {
    if (cancelled) return;
    if (nodes >= budget.maxNodes) { truncated = true; return; }
    if ((nodes & 1023) === 0) {
      if (opts.shouldCancel?.()) { cancelled = true; return; }
      if (now() - start > budget.maxTimeMs) { truncated = true; return; }
      reportProgress();
    }
    nodes++;

    // invariants 4 & 6: bound-prune ONLY once a finite feasible incumbent exists — and never on a
    // +∞ g_lb before then (such branches must reach a goal to set anyValidBoardFound => INFEASIBLE_INVENTORY).
    // Skip h computation when there is no incumbent (h is only needed for pruning).
    if (incumbent) {
      const g = placedCost();
      const h = remainderHeuristic(data, work, inventory);
      const f = addCost(g, h);
      if (compareCost(f, incumbent.cost) >= 0) return;
    }

    if (fastAnchorsConnected()) onComplete(); // invariant 3: keep searching past goals

    const cell = nextUndecidedFrontierCell();
    if (!cell) return; // every frontier cell decided => leaf

    // (a) INCLUDE: place each valid aspect (cheap obtainCost first). Infinity-safe compare:
    // `∞ - ∞ = NaN` would corrupt Array.sort, so compare without subtraction.
    const candidates: Aspect[] = [];
    for (const a of data.universe) if (validPlacement(cell, a)) candidates.push(a);
    candidates.sort((x, y) => {
      const cx = obtainCost(inventory, data, x);
      const cy = obtainCost(inventory, data, y);
      return cx === cy ? 0 : cx < cy ? -1 : 1;
    });
    const beam = budget.beam;
    const limited = beam ? candidates.slice(0, beam) : candidates;
    if (beam && limited.length < candidates.length) truncated = true;

    for (const a of limited) {
      setState(work, cell, { kind: 'PLACED', aspect: a, locked: false });
      placements.push({ key: hexKey(cell), aspect: a });
      dfs();
      placements.pop();
      setState(work, cell, { kind: 'EMPTY' });
      if (cancelled) return;
    }

    // (b) EXCLUDE: leave `cell` permanently EMPTY in this subtree (enables frontier-skipping optima)
    excluded.add(hexKey(cell));
    dfs();
    excluded.delete(hexKey(cell));
  };

  // Anytime seeding (spec §5.3): a quick Dijkstra-stitched candidate validated before use.
  seedIncumbent(opts, (cand) => {
    const v = validate(data, cand);
    if (v.valid && allAnchorsConnected(cand)) {
      const pls = solverPlacements(initial, cand);
      const alloc = allocate(inventory, data, demandOf(pls), allocBudget);
      if (alloc.feasible === true) {
        incumbent = { board: cloneBoard(cand), cost: { scarcity: alloc.scarcityCost, cells: pls.length }, alloc };
      }
    }
  });

  dfs();

  const timeMs = now() - start;
  const exhaustive = !truncated && !cancelled;
  // Capture incumbent in a typed const to work around TS5.6 narrowing: after calling closures that
  // write to `incumbent`, TS narrows the variable to `never`. An explicit cast resets the type.
  type IncType = { board: Board; cost: Cost; alloc: AllocationResult } | null;
  const inc = incumbent as IncType;

  if (cancelled) {
    return inc
      ? { status: 'CANCELLED', board: inc.board, cost: inc.cost, allocation: inc.alloc, stats: { nodes, timeMs } }
      : { status: 'CANCELLED', stats: { nodes, timeMs } };
  }

  if (inc) {
    if (exhaustive && !anyUnknownCompetitive) {
      return { status: 'OPTIMAL', board: inc.board, cost: inc.cost, allocation: inc.alloc, stats: { nodes, timeMs } };
    }
    return { status: 'FEASIBLE_TIMEOUT', board: inc.board, cost: inc.cost, allocation: inc.alloc, stats: { nodes, timeMs } };
  }

  // no incumbent
  if (exhaustive) {
    if (!anyValidBoardFound) return { status: 'UNSAT_PROVEN', stats: { nodes, timeMs } };
    if (!anyUnknownCompetitive) return { status: 'INFEASIBLE_INVENTORY', stats: { nodes, timeMs } };
  }
  return { status: 'UNKNOWN_TIMEOUT', stats: { nodes, timeMs } };
}

// --- helpers ---

function cloneBoard(b: Board): Board {
  const nb = createBoard(b.radius);
  for (const [k, s] of b.cells) nb.cells.set(k, { ...s });
  return nb;
}

function demandOf(placements: ReadonlyArray<Placement>): Map<Aspect, number> {
  const d = new Map<Aspect, number>();
  for (const p of placements) d.set(p.aspect, (d.get(p.aspect) ?? 0) + 1);
  return d;
}

/** solver-placed cells = PLACED,locked=false present in `solved` that were EMPTY in `initial`. */
function solverPlacements(initial: Board, solved: Board): Placement[] {
  const out: Placement[] = [];
  for (const h of boardCells(solved.radius)) {
    const s = getState(solved, h);
    const i = getState(initial, h);
    if (s.kind === 'PLACED' && !s.locked && i.kind === 'EMPTY') out.push({ key: hexKey(h), aspect: s.aspect });
  }
  return out;
}

// Anytime seed (spec §5.3, Task 6.7): pairwise-Dijkstra stitched candidate. UNTRUSTED — the accept
// gate runs validate() + allAnchorsConnected + allocate before writing the incumbent, so a wrong
// seed is silently dropped. Gated on opts.seed (default off) so unit tests of the pure search
// are completely unaffected.
function seedIncumbent(opts: SolveOptions, accept: (board: Board) => void): void {
  if (!opts.seed) return;
  const { data, board: initial, inventory } = opts;
  const anchors = anchorCells(initial);
  if (anchors.length < 2) return;

  // Greedy chain: connect anchor[0] to each other anchor by the cheapest product-path,
  // laying placements onto a single candidate board so each later Dijkstra sees the
  // cells committed by earlier paths.
  const candidate = cloneBoard(initial);
  for (let i = 1; i < anchors.length; i++) {
    const path = cheapestProductPath(data, candidate, inventory, anchors[0]!, anchors[i]!);
    if (!path) return; // give up; exhaustive search still runs
    for (const { hex, aspect } of path) {
      if (getState(candidate, hex).kind === 'EMPTY') {
        setState(candidate, hex, { kind: 'PLACED', aspect, locked: false });
      }
    }
  }
  accept(candidate); // trusted gate: validate + feasibility checks happen inside
}

/**
 * Dijkstra over a "product graph" whose states are (cell, aspect) pairs.
 * Returns the cheapest aspect-labelled path of cells from `from` to `to`
 * (excluding the endpoints themselves), or null if no path exists.
 *
 * Edge weight = obtainCost(newAspect). UNTRUSTED — caller validates.
 */
function cheapestProductPath(
  data: AspectData,
  board: Board,
  inv: Inventory,
  from: { hex: Hex; aspect: Aspect },
  to: { hex: Hex; aspect: Aspect },
): Array<{ hex: Hex; aspect: Aspect }> | null {
  // Degenerate: if from and to are already adjacent and validly linked, no intermediate cells needed.
  for (const n of neighborsOf(from.hex)) {
    if (n.q === to.hex.q && n.r === to.hex.r && isValidLink(data, from.aspect, to.aspect)) {
      return [];
    }
  }

  type State = string; // `${hexKey}|${aspect}`
  const stateKey = (h: Hex, a: Aspect): State => `${hexKey(h)}|${a}`;
  const startState = stateKey(from.hex, from.aspect);

  const dist = new Map<State, number>([[startState, 0]]);
  // prev maps state -> {prevState, hex, aspect, existing} for path reconstruction.
  // existing=true means the cell was already placed (traversal only, not a new placement).
  const prev = new Map<State, { prevState: State; hex: Hex; aspect: Aspect; existing: boolean }>();
  const visited = new Set<State>();

  // Simple Dijkstra with linear scan for minimum (board radius <= 5, universe <= ~80 aspects,
  // so the priority queue is at most ~O(cells * aspects) ≈ 60*80 = 4800 entries — linear scan fine).
  for (;;) {
    // Find the unvisited state with minimum distance
    let minCost = Number.POSITIVE_INFINITY;
    let minState: State | null = null;
    for (const [s, d] of dist) {
      if (!visited.has(s) && d < minCost) {
        minCost = d;
        minState = s;
      }
    }
    if (minState === null) return null; // queue empty, no path

    visited.add(minState);
    const pipeIdx = minState.lastIndexOf('|');
    const hKey = minState.slice(0, pipeIdx);
    const curAspect = minState.slice(pipeIdx + 1) as Aspect;
    const curHex = parseHexKey(hKey);

    // Expand neighbours
    for (const n of neighborsOf(curHex)) {
      if (!isOnBoard(n, board.radius)) continue;
      const nk = hexKey(n);
      const ns = board.cells.get(nk);
      const nsKind = ns?.kind ?? 'EMPTY';

      // GOAL CHECK: n is the target anchor itself
      if (n.q === to.hex.q && n.r === to.hex.r) {
        if (isValidLink(data, curAspect, to.aspect)) {
          // Reconstruct path: only include newly-placed cells (not existing traversal steps).
          const path: Array<{ hex: Hex; aspect: Aspect }> = [];
          let cur: State = minState;
          while (cur !== startState) {
            const p = prev.get(cur)!;
            if (!p.existing) path.push({ hex: p.hex, aspect: p.aspect });
            cur = p.prevState;
          }
          path.reverse();
          return path;
        }
        continue; // not validly linked to target — don't expand through it
      }

      // DEAD cells cannot be traversed or placed on.
      if (nsKind === 'DEAD') continue;

      if (nsKind === 'PLACED' || nsKind === 'ANCHOR') {
        // Already-filled cells (from prior seed paths or the initial board) can be
        // traversed for free if the current aspect links validly to their aspect.
        // No new placement is needed; only the endpoint matters for connectivity.
        const filledAspect = (ns as { aspect: Aspect }).aspect;
        if (filledAspect === curAspect) continue; // same aspect — invalid link
        if (!isValidLink(data, curAspect, filledAspect)) continue;
        const ns2 = stateKey(n, filledAspect);
        if (visited.has(ns2)) continue;
        const newCost = minCost; // traversal is free (cell already placed)
        const oldCost = dist.get(ns2) ?? Number.POSITIVE_INFINITY;
        if (newCost < oldCost) {
          dist.set(ns2, newCost);
          // prevEntry hex=n, aspect=filledAspect, but mark as traversal (not a new placement)
          // by storing the existing state. The path reconstruction will skip non-new cells.
          prev.set(ns2, { prevState: minState, hex: n, aspect: filledAspect, existing: true });
        }
        continue;
      }

      // EMPTY cell: try each aspect for placing at n
      for (const b of data.universe) {
        if (b === curAspect) continue; // must differ from predecessor
        if (!isValidLink(data, curAspect, b)) continue;
        // Validate against all existing filled neighbors of n on the board
        if (!validAgainstBoard(data, board, b, n)) continue;

        const ns2 = stateKey(n, b);
        if (visited.has(ns2)) continue;
        const newCost = minCost + obtainCost(inv, data, b);
        const oldCost = dist.get(ns2) ?? Number.POSITIVE_INFINITY;
        if (newCost < oldCost) {
          dist.set(ns2, newCost);
          prev.set(ns2, { prevState: minState, hex: n, aspect: b, existing: false });
        }
      }
    }
  }
}

/**
 * Returns true if placing aspect `b` at hex `n` is consistent with all existing
 * filled (ANCHOR or PLACED) neighbors of `n` on the board.
 */
function validAgainstBoard(data: AspectData, board: Board, b: Aspect, n: Hex): boolean {
  for (const m of neighborsOf(n)) {
    if (!isOnBoard(m, board.radius)) continue;
    const ms = board.cells.get(hexKey(m));
    if (!ms) continue; // EMPTY
    if (ms.kind !== 'ANCHOR' && ms.kind !== 'PLACED') continue; // DEAD/EMPTY impose no constraint
    const ma = ms.aspect;
    if (ma === b) return false; // same aspect adjacent
    if (!isValidLink(data, b, ma)) return false;
  }
  return true;
}
