import { describe, expect, it } from 'bun:test';
import { createBoard, getState, setState, filledCells, anchorCells } from '../../app/src/core/board';

describe('board model', () => {
  it('creates a board where every on-board cell defaults to EMPTY', () => {
    const b = createBoard(2);
    expect(getState(b, { q: 0, r: 0 })).toEqual({ kind: 'EMPTY' });
    expect(getState(b, { q: 2, r: 0 })).toEqual({ kind: 'EMPTY' });
  });
  it('throws for off-board access', () => {
    const b = createBoard(2);
    expect(() => getState(b, { q: 3, r: 0 })).toThrow();
  });
  it('stores and reads back states', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'void', locked: false });
    setState(b, { q: -1, r: 0 }, { kind: 'DEAD' });
    expect(getState(b, { q: 0, r: 0 })).toEqual({ kind: 'ANCHOR', aspect: 'air' });
    expect(anchorCells(b).map((c) => c.hex)).toEqual([{ q: 0, r: 0 }]);
    expect(filledCells(b).map((c) => c.aspect).sort()).toEqual(['air', 'void']);
  });
  it('setting EMPTY clears a stored cell', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'PLACED', aspect: 'air', locked: true });
    setState(b, { q: 0, r: 0 }, { kind: 'EMPTY' });
    expect(getState(b, { q: 0, r: 0 })).toEqual({ kind: 'EMPTY' });
  });
});
