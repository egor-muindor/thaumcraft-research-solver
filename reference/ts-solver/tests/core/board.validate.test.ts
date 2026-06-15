import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { createBoard, setState, validate, allAnchorsConnected, isComplete } from '../../app/src/core/board';

const data = buildAspectData();

describe('validate (spec §3)', () => {
  it('valid: two anchors joined by a single valid chain', () => {
    const b = createBoard(2);
    // air(0,0) - void(1,0) ... void=air+entropy so air-void valid; entropy anchor at (2,0): void-entropy valid
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'void', locked: false });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const v = validate(data, b);
    expect(v.valid).toBe(true);
    expect(allAnchorsConnected(b)).toBe(true);
    expect(isComplete(data, b)).toBe(true);
  });

  it('SAME_ASPECT_ADJACENT when identical aspects touch', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'air', locked: false });
    const v = validate(data, b);
    expect(v.valid).toBe(false);
    expect(v.errors.some((e) => e.type === 'SAME_ASPECT_ADJACENT')).toBe(true);
  });

  it('INVALID_LINK when adjacent aspects are not graph-connected', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'earth', locked: false }); // air-earth not an edge
    const v = validate(data, b);
    expect(v.errors.some((e) => e.type === 'INVALID_LINK')).toBe(true);
  });

  it('detects an incidental invalid touch between two separate branches', () => {
    const b = createBoard(2);
    // Place two valid pairs that happen to touch invalidly.
    setState(b, { q: 0, r: 0 }, { kind: 'PLACED', aspect: 'air', locked: false });
    setState(b, { q: 0, r: 1 }, { kind: 'PLACED', aspect: 'earth', locked: false }); // adjacency (0,0)-(0,1) invalid
    const v = validate(data, b);
    expect(v.valid).toBe(false);
  });

  it('ANCHORS_DISCONNECTED when anchors are in separate filled components', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' }); // no chain between them
    const v = validate(data, b);
    expect(v.errors.some((e) => e.type === 'ANCHORS_DISCONNECTED')).toBe(true);
    expect(allAnchorsConnected(b)).toBe(false);
    expect(isComplete(data, b)).toBe(false);
  });

  it('a locked island (no anchor) does NOT trigger ANCHORS_DISCONNECTED', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'void', locked: false });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    setState(b, { q: -2, r: 2 }, { kind: 'PLACED', aspect: 'fire', locked: true }); // isolated locked island, valid
    const v = validate(data, b);
    expect(v.valid).toBe(true); // island is allowed (spec §3.4)
  });
});
