import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { createBoard, setState, getState, serializeBoard, deserializeBoard, BOARD_SCHEMA_VERSION } from '../../app/src/core/board';

const data = buildAspectData();

describe('board serialization (spec §2.3)', () => {
  it('round-trips a board, storing only non-EMPTY cells', () => {
    const b = createBoard(3);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'void', locked: true });
    setState(b, { q: -1, r: 0 }, { kind: 'DEAD' });
    const json = serializeBoard(b);
    expect(json.schemaVersion).toBe(BOARD_SCHEMA_VERSION);
    expect(json.radius).toBe(3);
    expect(json.cells).toHaveLength(3);
    const b2 = deserializeBoard(data, json);
    expect(getState(b2, { q: 1, r: 0 })).toEqual({ kind: 'PLACED', aspect: 'void', locked: true });
    expect(getState(b2, { q: -1, r: 0 })).toEqual({ kind: 'DEAD' });
  });

  it('rejects an unknown aspect id with a clear error', () => {
    const bad = { schemaVersion: BOARD_SCHEMA_VERSION, radius: 2, cells: [{ coord: '0,0', state: 'ANCHOR', aspect: 'nope' }] };
    expect(() => deserializeBoard(data, bad)).toThrow(/nope/);
  });

  it('rejects out-of-radius coords', () => {
    const bad = { schemaVersion: BOARD_SCHEMA_VERSION, radius: 2, cells: [{ coord: '9,9', state: 'DEAD' }] };
    expect(() => deserializeBoard(data, bad)).toThrow();
  });

  it('rejects malformed input without crashing', () => {
    expect(() => deserializeBoard(data, null)).toThrow();
    expect(() => deserializeBoard(data, { radius: 2 })).toThrow();
  });
});
