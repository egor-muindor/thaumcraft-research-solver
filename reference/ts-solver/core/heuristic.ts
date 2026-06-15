import type { AspectData } from '../data/aspects';
import type { Cost } from './cost';
import type { Board } from './board';
import { filledCells, anchorCells } from './board';
import { type Hex, hexKey, neighborsOf, boardCells, isOnBoard } from './hex';
import { globalMinObtain, type Inventory } from './inventory';
import { steinerNodeWeighted, type SteinerGraph } from './steiner';

interface CellGraph {
  ids: Map<string, number>; // hexKey -> node id (only usable cells: non-DEAD)
  hexes: Hex[];
  adj: number[][];
  filledKeys: Set<string>; // anchors + placed (weight 0)
  terminals: number[]; // representative node per anchor-component
}

function buildCellGraph(board: Board): CellGraph {
  const ids = new Map<string, number>();
  const hexes: Hex[] = [];
  for (const h of boardCells(board.radius)) {
    const s = board.cells.get(hexKey(h));
    if (s && s.kind === 'DEAD') continue; // dead excluded
    ids.set(hexKey(h), hexes.length);
    hexes.push(h);
  }
  const adj: number[][] = hexes.map(() => []);
  for (let i = 0; i < hexes.length; i++) {
    for (const n of neighborsOf(hexes[i]!)) {
      if (!isOnBoard(n, board.radius)) continue;
      const j = ids.get(hexKey(n));
      if (j !== undefined) adj[i]!.push(j);
    }
  }
  const filled = filledCells(board);
  const filledKeys = new Set(filled.map((c) => hexKey(c.hex)));

  // anchor-components: BFS over filled adjacency, keep one representative id per component that has >=1 anchor
  const anchorKeys = new Set(anchorCells(board).map((a) => hexKey(a.hex)));
  const compOf = new Map<string, number>();
  let comp = 0;
  for (const c of filled) {
    const k = hexKey(c.hex);
    if (compOf.has(k)) continue;
    const stack = [c.hex];
    compOf.set(k, comp);
    while (stack.length) {
      const cur = stack.pop()!;
      for (const n of neighborsOf(cur)) {
        const nk = hexKey(n);
        if (filledKeys.has(nk) && !compOf.has(nk)) { compOf.set(nk, comp); stack.push(n); }
      }
    }
    comp++;
  }
  const compHasAnchor = new Array(comp).fill(false);
  for (const k of anchorKeys) compHasAnchor[compOf.get(k)!] = true;
  const repByComp = new Map<number, number>();
  for (const [k, ci] of compOf) if (compHasAnchor[ci] && !repByComp.has(ci)) repByComp.set(ci, ids.get(k)!);
  const terminals = [...repByComp.values()];

  return { ids, hexes, adj, filledKeys, terminals };
}

function steinerWith(graph: CellGraph, freeWeight: number): number {
  // Contract a whole anchor-component to its representative: every filled cell weight 0,
  // and connectivity among a component's cells already holds via adjacency (all weight 0),
  // so using one representative per component as a terminal is exact.
  const g: SteinerGraph = {
    size: graph.hexes.length,
    neighbors: (v) => graph.adj[v]!,
    weight: (v) => (graph.filledKeys.has(hexKey(graph.hexes[v]!)) ? 0 : freeWeight),
    terminals: graph.terminals,
  };
  return steinerNodeWeighted(g);
}

export function remainderHeuristic(data: AspectData, board: Board, inv: Inventory): Cost {
  const graph = buildCellGraph(board);
  if (graph.terminals.length <= 1) return { scarcity: 0, cells: 0 };

  const w = globalMinObtain(inv, data); // admissible per-free-cell weight (>=0, may be 0)
  const totalScarcity = steinerWith(graph, w);
  const totalCells = steinerWith(graph, 1);

  // Subtract the contribution of already-paid (filled) cells = 0, so the tree weight IS the remainder.
  // hCells = number of inner FREE cells = (cells-weighted tree) minus terminals' own 0 weight already excluded.
  const hScarcity = totalScarcity; // free cells only contribute (filled weight 0)
  const hCells = Number.isFinite(totalCells) ? totalCells : Number.POSITIVE_INFINITY;
  return { scarcity: hScarcity, cells: hCells };
}
