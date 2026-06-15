import type { Aspect, AspectData } from '../data/aspects';
import { mult } from './aspectGraph';

export const DEFAULT_THRESHOLD = 50;
export const BASE = 1; // spec §4.1: base > 0
export const K = 1; // spec §4.1: k >= 0

export interface Inventory {
  /** Non-negative integer counts. Absent key => 0. */
  readonly supply: ReadonlyMap<Aspect, number>;
  /** Strictly > 0. */
  readonly threshold: number;
}

export function makeInventory(entries: ReadonlyArray<readonly [Aspect, number]>, threshold = DEFAULT_THRESHOLD): Inventory {
  return { supply: new Map(entries), threshold };
}

export function validateInventory(inv: Inventory): void {
  if (!(inv.threshold > 0) || !Number.isFinite(inv.threshold)) {
    throw new Error(`threshold must be > 0, got ${inv.threshold}`);
  }
  for (const [a, n] of inv.supply) {
    if (!Number.isInteger(n) || n < 0) {
      throw new Error(`supply['${a}'] must be a non-negative integer, got ${n}`);
    }
  }
}

function supplyOf(inv: Inventory, a: Aspect): number {
  return inv.supply.get(a) ?? 0;
}

export function directPenalty(inv: Inventory, _data: AspectData, a: Aspect): number {
  const s = supplyOf(inv, a);
  if (s >= inv.threshold) return 0;
  if (s > 0) return BASE + K * (inv.threshold - s);
  return Number.POSITIVE_INFINITY;
}

// Keyed on (AspectData, Inventory): obtainCost depends on BOTH the recipe graph and the supply,
// so omitting AspectData would return stale costs when an Inventory is reused with different data
// (and break g_lb/globalMinObtain admissibility in the solver). Nested WeakMaps auto-GC.
const obtainCache = new WeakMap<AspectData, WeakMap<Inventory, Map<Aspect, number>>>();

export function obtainCost(inv: Inventory, data: AspectData, a: Aspect): number {
  let byInv = obtainCache.get(data);
  if (!byInv) {
    byInv = new WeakMap();
    obtainCache.set(data, byInv);
  }
  let cache = byInv.get(inv);
  if (!cache) {
    cache = new Map();
    byInv.set(inv, cache);
  }
  return obtainRec(inv, data, a, cache, new Set());
}

function obtainRec(inv: Inventory, data: AspectData, a: Aspect, cache: Map<Aspect, number>, stack: Set<Aspect>): number {
  const memo = cache.get(a);
  if (memo !== undefined) return memo;
  if (stack.has(a)) return Number.POSITIVE_INFINITY; // cycle guard (data is a DAG; defensive)
  const direct = directPenalty(inv, data, a);
  let best = direct;
  const recipe = data.combinations.get(a);
  if (recipe) {
    stack.add(a);
    const craft = obtainRec(inv, data, recipe[0], cache, stack) + obtainRec(inv, data, recipe[1], cache, stack);
    stack.delete(a);
    if (craft < best) best = craft;
  }
  cache.set(a, best);
  return best;
}

export function globalMinObtain(inv: Inventory, data: AspectData): number {
  let min = Number.POSITIVE_INFINITY;
  for (const a of data.universe) {
    const c = obtainCost(inv, data, a);
    if (c < min) min = c;
  }
  return min;
}

// mult re-exported for the allocator below.
export { mult };

export interface AllocBudget {
  /** Max DFS nodes before returning feasible:'unknown'. Default 200_000. */
  readonly maxNodes: number;
}

export interface AllocationResult {
  readonly feasible: boolean | 'unknown';
  readonly scarcityCost: number;
  readonly craftOps: number;
  /** Actual per-aspect direct draws from supply (= direct[X]); subtracted by "Subtract used". */
  readonly leafConsumption: ReadonlyMap<Aspect, number>;
}

/** Reverse-topological order: an aspect appears before any of its recipe components. */
function reverseTopoOrder(data: AspectData, aspects: Iterable<Aspect>): Aspect[] {
  const order: Aspect[] = [];
  const seen = new Set<Aspect>();
  const visit = (a: Aspect): void => {
    if (seen.has(a)) return;
    seen.add(a);
    const recipe = data.combinations.get(a);
    if (recipe) for (const c of recipe) visit(c);
    order.push(a); // components pushed before parent => reverse at the end
  };
  for (const a of aspects) visit(a);
  order.reverse(); // now parents precede components
  return order;
}

const ALLOC_INFEASIBLE = Symbol('infeasible');
type AllocSub = { cost: number; craftOps: number; direct: Map<Aspect, number> };

export function allocate(
  inv: Inventory,
  data: AspectData,
  demand: ReadonlyMap<Aspect, number>,
  budget: AllocBudget = { maxNodes: 200_000 },
): AllocationResult {
  validateInventory(inv);

  // Aspects relevant to this demand = demand keys plus their full component closure.
  const order = reverseTopoOrder(data, demand.keys()); // parents precede components
  const idx = new Map<Aspect, number>(order.map((a, i) => [a, i]));
  const n = order.length;
  const penalty = order.map((a) => directPenalty(inv, data, a));
  const supplyArr = order.map((a) => inv.supply.get(a) ?? 0);
  const need0 = order.map((a) => demand.get(a) ?? 0);

  // Memoized exact DP. rec(i, need) = optimal allocation of indices [i..n) given residual `need`
  // (only entries >= i are meaningful; crafts at j>=i push demand to indices > j by topo order).
  // Memo key = i + suffix-needs => identical subproblems are solved once (spec §4.3 "memoized").
  const memo = new Map<string, AllocSub | typeof ALLOC_INFEASIBLE>();
  let nodes = 0;
  let budgetExhausted = false;

  const rec = (i: number, need: number[]): AllocSub | typeof ALLOC_INFEASIBLE => {
    if (budgetExhausted) return ALLOC_INFEASIBLE;
    if (i === n) return { cost: 0, craftOps: 0, direct: new Map() };
    const key = `${i}|${need.slice(i).join(',')}`;
    const cached = memo.get(key);
    if (cached !== undefined) return cached;
    if (++nodes > budget.maxNodes) { budgetExhausted = true; return ALLOC_INFEASIBLE; }

    const X = order[i]!;
    const want = need[i]!;
    const avail = supplyArr[i]!;
    const pen = penalty[i]!;
    const recipe = data.combinations.get(X);
    const maxDirect = Math.min(want, avail);

    let best: AllocSub | typeof ALLOC_INFEASIBLE = ALLOC_INFEASIBLE;
    for (let d = maxDirect; d >= 0; d--) {
      const c = want - d;
      if (c > 0 && !recipe) continue;                  // primal cannot be crafted
      if (d > 0 && !Number.isFinite(pen)) continue;    // unreachable (+Inf penalty)
      const need2 = need.slice();
      if (c > 0 && recipe) {
        for (const comp of new Set(recipe)) {
          const j = idx.get(comp)!;                    // j > i by topo order
          need2[j] = (need2[j] ?? 0) + mult(data, comp, X) * c;
        }
      }
      const sub = rec(i + 1, need2);
      if (budgetExhausted) return ALLOC_INFEASIBLE;    // BLOCKER-fix: never memoize/return past exhaustion
      if (sub === ALLOC_INFEASIBLE) continue;
      const cost = (d > 0 ? d * pen : 0) + sub.cost;
      const craftOps = c + sub.craftOps;
      if (best === ALLOC_INFEASIBLE || cost < best.cost || (cost === best.cost && craftOps < best.craftOps)) {
        const direct = new Map(sub.direct);
        if (d > 0) direct.set(X, d);
        best = { cost, craftOps, direct };
      }
    }
    memo.set(key, best);
    return best;
  };

  const result = rec(0, need0);

  // Budget exhaustion ALWAYS wins: an interrupted search is unproven, even if a feasible split was seen.
  if (budgetExhausted) {
    return { feasible: 'unknown', scarcityCost: Number.POSITIVE_INFINITY, craftOps: 0, leafConsumption: new Map() };
  }
  if (result === ALLOC_INFEASIBLE) {
    return { feasible: false, scarcityCost: Number.POSITIVE_INFINITY, craftOps: 0, leafConsumption: new Map() };
  }
  const leaf = new Map<Aspect, number>();
  for (const [a, dd] of result.direct) if (dd > 0) leaf.set(a, dd);
  return { feasible: true, scarcityCost: result.cost, craftOps: result.craftOps, leafConsumption: leaf };
}
