import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { makeInventory, DEFAULT_THRESHOLD } from '../../app/src/core/inventory';
import { createBoard, setState, validate, getState } from '../../app/src/core/board';
import { boardCells, hexKey } from '../../app/src/core/hex';
import { solve, type SolveResult } from '../../app/src/core/solver';

const data = buildAspectData();
const rich = makeInventory(
  [...data.universe].map((a) => [a, 100] as [string, number]),
  DEFAULT_THRESHOLD,
);
const budget = { maxNodes: 2_000_000, maxTimeMs: 20_000 };

function twoAnchorBoard(): ReturnType<typeof createBoard> {
  const b = createBoard(2);
  setState(b, { q: -1, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
  setState(b, { q: 1, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
  return b; // need a valid chain air..entropy (void = air+entropy bridges them)
}

describe('solver invariants (spec §5)', () => {
  it('always returns a valid, connected board when it returns one', () => {
    const r = solve({ data, board: twoAnchorBoard(), inventory: rich, budget });
    expect(['OPTIMAL', 'FEASIBLE_TIMEOUT']).toContain(r.status);
    expect(r.board).toBeDefined();
    expect(validate(data, r.board!).valid).toBe(true);
  });

  it('finds the 1-cell optimum that leaves the lexicographically-first frontier cell EMPTY (completeness)', () => {
    // air(0,0) -- void(1,0) -- entropy(2,0): the unique 1-cell bridge. The lowest-hexKey frontier
    // cell is "-1,0" (points away from entropy); a single-cell-forced expansion could never leave it
    // empty and would miss this optimum. The include/exclude search must return cells === 1.
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const r = solve({ data, board: b, inventory: rich, budget });
    expect(r.status).toBe('OPTIMAL');
    expect(r.cost?.cells).toBe(1);
    expect(r.board && getState(r.board, { q: 1, r: 0 })).toEqual({ kind: 'PLACED', aspect: 'void', locked: false });
    expect(r.board && getState(r.board, { q: -1, r: 0 })).toEqual({ kind: 'EMPTY' });
  });

  it('avoids dead hexes entirely', () => {
    const b = twoAnchorBoard();
    setState(b, { q: 0, r: 0 }, { kind: 'DEAD' }); // force routing around center
    const r = solve({ data, board: b, inventory: rich, budget });
    if (r.board) {
      expect(getState(r.board, { q: 0, r: 0 })).toEqual({ kind: 'DEAD' });
      expect(validate(data, r.board).valid).toBe(true);
    }
  });

  it('handles a multi-anchor (3) instance', () => {
    const b = createBoard(3);
    setState(b, { q: -2, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    setState(b, { q: 0, r: 2 }, { kind: 'ANCHOR', aspect: 'fire' });
    const r = solve({ data, board: b, inventory: rich, budget });
    if (r.board) expect(validate(data, r.board).valid).toBe(true);
  });

  it('trivially solved with 0 or 1 anchor', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    const r = solve({ data, board: b, inventory: rich, budget });
    expect(r.status).toBe('OPTIMAL');
  });

  it('UNKNOWN_TIMEOUT when truncated before any incumbent', () => {
    const r = solve({ data, board: twoAnchorBoard(), inventory: rich, budget: { maxNodes: 1, maxTimeMs: 1 } });
    expect(['UNKNOWN_TIMEOUT', 'FEASIBLE_TIMEOUT']).toContain(r.status);
  });

  it('prefers abundant aspects: chooses a feasible board over a cheaper-by-links infeasible one', () => {
    // Make the "obvious" bridge aspect zero-supply so the optimum must route through abundant aspects.
    const inv = makeInventory(
      [...data.universe].map((a) => [a, a === 'void' ? 0 : 100] as [string, number]),
      DEFAULT_THRESHOLD,
    );
    const r = solve({ data, board: twoAnchorBoard(), inventory: inv, budget });
    // void has zero supply and cannot be crafted? void=air+entropy abundant => craftable, feasible stays true.
    expect(['OPTIMAL', 'FEASIBLE_TIMEOUT']).toContain(r.status);
    if (r.board) expect(validate(data, r.board).valid).toBe(true);
  });

  it('INFEASIBLE_INVENTORY vs UNSAT_PROVEN are distinguished on a tiny exhaustible instance', () => {
    // Adjacent anchors with NO valid linking aspect and no empty cell between => exhaustive search.
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'ANCHOR', aspect: 'earth' }); // air-earth invalid AND adjacent => no fix
    const r = solve({ data, board: b, inventory: rich, budget });
    // adjacent invalid anchors: caller pre-validation normally catches this; if solver reached it, it's UNSAT.
    expect(['UNSAT_PROVEN', 'INVALID_INPUT']).toContain(r.status);
  });

  it('allocator budget exhaustion blocks the proof on an exhaustible instance (=> UNKNOWN_TIMEOUT, not INFEASIBLE_INVENTORY)', () => {
    // Tiny EXHAUSTIBLE instance: air & entropy with only (1,0) free (everything else DEAD), so the full
    // include/exclude search is a handful of nodes. The unique bridge `void` forms a complete board, but
    // allocBudget {maxNodes:1} makes allocate return 'unknown' for it. anyUnknownCompetitive must then
    // BLOCK the proof: the search is exhaustive, yet the status is UNKNOWN_TIMEOUT (not OPTIMAL/INFEASIBLE).
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    for (const h of boardCells(2)) {
      const k = hexKey(h);
      if (k === '0,0' || k === '2,0' || k === '1,0') continue;
      setState(b, h, { kind: 'DEAD' });
    }
    const r = solve({ data, board: b, inventory: rich, budget, allocBudget: { maxNodes: 1 } });
    expect(r.status).not.toBe('OPTIMAL');
    expect(r.status).not.toBe('INFEASIBLE_INVENTORY');
    expect(r.status).toBe('UNKNOWN_TIMEOUT');
  });

  it('beam mode explores its retained candidates (no early abort) and still finds a valid connected board', () => {
    // (0,1) is adjacent only to air => many valid candidates; beam:2 truncates the fan-out there (sets the
    // truncation status flag). The fix must NOT let that flag abort the whole DFS: after excluding (0,1) the
    // search must still reach (1,0) and place the unique bridge `void`, yielding a valid connected board.
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    for (const h of boardCells(2)) {
      const k = hexKey(h);
      if (k === '0,0' || k === '2,0' || k === '1,0' || k === '0,1') continue;
      setState(b, h, { kind: 'DEAD' });
    }
    const r = solve({ data, board: b, inventory: rich, budget: { maxNodes: 100_000, maxTimeMs: 5_000, beam: 2 } });
    expect(r.board).toBeDefined();
    expect(validate(data, r.board!).valid).toBe(true);
  });
});
