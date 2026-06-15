export interface Cost {
  readonly scarcity: number; // may be +Infinity
  readonly cells: number;
}

export const ZERO_COST: Cost = { scarcity: 0, cells: 0 };
export const INF_COST: Cost = { scarcity: Number.POSITIVE_INFINITY, cells: Number.POSITIVE_INFINITY };

/** The single comparator used for ALL solver comparisons/pruning (spec §5.2). */
export function compareCost(a: Cost, b: Cost): number {
  if (a.scarcity !== b.scarcity) return a.scarcity < b.scarcity ? -1 : 1;
  if (a.cells !== b.cells) return a.cells < b.cells ? -1 : 1;
  return 0;
}

export function lessThan(a: Cost, b: Cost): boolean {
  return compareCost(a, b) < 0;
}

export function addCost(a: Cost, b: Cost): Cost {
  return { scarcity: a.scarcity + b.scarcity, cells: a.cells + b.cells };
}
