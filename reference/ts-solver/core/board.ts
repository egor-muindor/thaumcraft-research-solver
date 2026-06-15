import type { Aspect, AspectData } from '../data/aspects';
import { type Hex, hexKey, parseHexKey, isOnBoard, boardCells, neighborsOf } from './hex';
import { isValidLink } from './aspectGraph';

export type CellState =
  | { kind: 'DEAD' }
  | { kind: 'ANCHOR'; aspect: Aspect }
  | { kind: 'EMPTY' }
  | { kind: 'PLACED'; aspect: Aspect; locked: boolean };

export interface Board {
  readonly radius: number;
  /** Only non-EMPTY cells are stored; absent on-board key => EMPTY. */
  readonly cells: Map<string, CellState>;
}

export function createBoard(radius: number): Board {
  if (!Number.isInteger(radius) || radius < 2 || radius > 5) {
    throw new Error(`radius must be an integer 2..5, got ${radius}`);
  }
  return { radius, cells: new Map() };
}

export function getState(board: Board, h: Hex): CellState {
  if (!isOnBoard(h, board.radius)) throw new Error(`hex ${hexKey(h)} is off board (R=${board.radius})`);
  return board.cells.get(hexKey(h)) ?? { kind: 'EMPTY' };
}

export function setState(board: Board, h: Hex, s: CellState): void {
  if (!isOnBoard(h, board.radius)) throw new Error(`hex ${hexKey(h)} is off board (R=${board.radius})`);
  if (s.kind === 'EMPTY') board.cells.delete(hexKey(h));
  else board.cells.set(hexKey(h), s);
}

export interface FilledCell { hex: Hex; aspect: Aspect; locked: boolean; isAnchor: boolean; }

export function filledCells(board: Board): FilledCell[] {
  const out: FilledCell[] = [];
  for (const h of boardCells(board.radius)) {
    const s = getState(board, h);
    if (s.kind === 'ANCHOR') out.push({ hex: h, aspect: s.aspect, locked: false, isAnchor: true });
    else if (s.kind === 'PLACED') out.push({ hex: h, aspect: s.aspect, locked: s.locked, isAnchor: false });
  }
  return out;
}

export function anchorCells(board: Board): Array<{ hex: Hex; aspect: Aspect }> {
  return filledCells(board).filter((c) => c.isAnchor).map((c) => ({ hex: c.hex, aspect: c.aspect }));
}

/** On-board neighbors that are filled (ANCHOR or PLACED). */
export function filledNeighbors(board: Board, h: Hex): FilledCell[] {
  const out: FilledCell[] = [];
  for (const n of neighborsOf(h)) {
    if (!isOnBoard(n, board.radius)) continue;
    const s = getState(board, n);
    if (s.kind === 'ANCHOR') out.push({ hex: n, aspect: s.aspect, locked: false, isAnchor: true });
    else if (s.kind === 'PLACED') out.push({ hex: n, aspect: s.aspect, locked: s.locked, isAnchor: false });
  }
  return out;
}

export type ValidationErrorType =
  | 'INVALID_LINK' | 'SAME_ASPECT_ADJACENT' | 'ANCHORS_DISCONNECTED' | 'PLACED_ON_DEAD' | 'MALFORMED';

export interface ValidationError { type: ValidationErrorType; cells: Hex[]; }
export interface ValidationResult { valid: boolean; errors: ValidationError[]; }

export function validate(data: AspectData, board: Board): ValidationResult {
  const errors: ValidationError[] = [];
  const filled = filledCells(board);
  const filledKeys = new Set(filled.map((c) => hexKey(c.hex)));
  const aspectAt = new Map(filled.map((c) => [hexKey(c.hex), c.aspect]));

  // 1) pairwise adjacency validity (each undirected pair once)
  for (const c of filled) {
    for (const n of neighborsOf(c.hex)) {
      const nk = hexKey(n);
      if (!filledKeys.has(nk)) continue;
      if (hexKey(c.hex) >= nk) continue; // dedupe ordered pair
      const a = c.aspect;
      const b = aspectAt.get(nk)!;
      if (a === b) errors.push({ type: 'SAME_ASPECT_ADJACENT', cells: [c.hex, n] });
      else if (!isValidLink(data, a, b)) errors.push({ type: 'INVALID_LINK', cells: [c.hex, n] });
    }
  }

  // 2) anchors connectivity (>=2 anchors must share one filled component)
  if (!anchorsConnectedInternal(board)) {
    errors.push({ type: 'ANCHORS_DISCONNECTED', cells: anchorCells(board).map((a) => a.hex) });
  }

  return { valid: errors.length === 0, errors };
}

function anchorsConnectedInternal(board: Board): boolean {
  const anchors = anchorCells(board);
  if (anchors.length <= 1) return true;
  const filled = new Set(filledCells(board).map((c) => hexKey(c.hex)));
  // BFS from first anchor over filled adjacency
  const start = hexKey(anchors[0]!.hex);
  const seen = new Set<string>([start]);
  const queue = [anchors[0]!.hex];
  while (queue.length) {
    const cur = queue.pop()!;
    for (const n of neighborsOf(cur)) {
      const nk = hexKey(n);
      if (filled.has(nk) && !seen.has(nk)) {
        seen.add(nk);
        queue.push(n);
      }
    }
  }
  return anchors.every((a) => seen.has(hexKey(a.hex)));
}

export function allAnchorsConnected(board: Board): boolean {
  return anchorsConnectedInternal(board);
}

/** A finished solution: fully valid AND all anchors in one component. */
export function isComplete(data: AspectData, board: Board): boolean {
  return validate(data, board).valid;
}

export const BOARD_SCHEMA_VERSION = 1;

export interface SerializedCell { coord: string; state: 'DEAD' | 'ANCHOR' | 'PLACED'; aspect?: Aspect; locked?: boolean; }
export interface SerializedBoard { schemaVersion: number; radius: number; cells: SerializedCell[]; }

export function serializeBoard(board: Board): SerializedBoard {
  const cells: SerializedCell[] = [];
  for (const [key, s] of board.cells) {
    if (s.kind === 'EMPTY') continue;
    if (s.kind === 'DEAD') cells.push({ coord: key, state: 'DEAD' });
    else if (s.kind === 'ANCHOR') cells.push({ coord: key, state: 'ANCHOR', aspect: s.aspect });
    else cells.push({ coord: key, state: 'PLACED', aspect: s.aspect, locked: s.locked });
  }
  return { schemaVersion: BOARD_SCHEMA_VERSION, radius: board.radius, cells };
}

export function deserializeBoard(data: AspectData, raw: unknown): Board {
  if (typeof raw !== 'object' || raw === null) throw new Error('board: not an object');
  const obj = raw as Record<string, unknown>;
  const radius = obj.radius;
  if (!Number.isInteger(radius) || (radius as number) < 2 || (radius as number) > 5) {
    throw new Error(`board: bad radius ${String(radius)}`);
  }
  // schemaVersion migration hook (only v1 exists today).
  const ver = obj.schemaVersion;
  if (ver !== BOARD_SCHEMA_VERSION) throw new Error(`board: unsupported schemaVersion ${String(ver)}`);
  if (!Array.isArray(obj.cells)) throw new Error('board: cells must be an array');

  const board = createBoard(radius as number);
  for (const c of obj.cells as unknown[]) {
    if (typeof c !== 'object' || c === null) throw new Error('board: bad cell');
    const cell = c as Record<string, unknown>;
    if (typeof cell.coord !== 'string') throw new Error('board: cell.coord must be a string');
    const hex = parseHexKey(cell.coord);
    if (!isOnBoard(hex, board.radius)) throw new Error(`board: coord ${cell.coord} off radius ${board.radius}`);
    const checkAspect = (a: unknown): Aspect => {
      if (typeof a !== 'string' || !data.universe.has(a)) throw new Error(`board: unknown aspect '${String(a)}'`);
      return a;
    };
    switch (cell.state) {
      case 'DEAD': setState(board, hex, { kind: 'DEAD' }); break;
      case 'ANCHOR': setState(board, hex, { kind: 'ANCHOR', aspect: checkAspect(cell.aspect) }); break;
      case 'PLACED': setState(board, hex, { kind: 'PLACED', aspect: checkAspect(cell.aspect), locked: cell.locked === true }); break;
      default: throw new Error(`board: bad cell.state '${String(cell.state)}'`);
    }
  }
  return board;
}
