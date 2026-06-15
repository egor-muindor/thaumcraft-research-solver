import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { makeInventory, DEFAULT_THRESHOLD } from '../../app/src/core/inventory';
import { createBoard, setState } from '../../app/src/core/board';
import { solveWithValidation } from '../../app/src/core/solver';

const data = buildAspectData();
const inv = makeInventory([...data.universe].map((a) => [a, 100] as [string, number]), DEFAULT_THRESHOLD);

describe('pre-validation (spec §3)', () => {
  it('returns INVALID_INPUT when starting anchors are adjacent but unlinkable', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'ANCHOR', aspect: 'earth' }); // adjacent + invalid link, unfixable
    const r = solveWithValidation({ data, board: b, inventory: inv, budget: { maxNodes: 1000, maxTimeMs: 100 } });
    expect(r.status).toBe('INVALID_INPUT');
    expect(r.errors?.some((e) => e.type === 'INVALID_LINK' || e.type === 'SAME_ASPECT_ADJACENT')).toBe(true);
  });

  it('does not reject a solvable start (disconnected anchors with room to route)', () => {
    const b = createBoard(2);
    setState(b, { q: -1, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const r = solveWithValidation({ data, board: b, inventory: inv, budget: { maxNodes: 1_000_000, maxTimeMs: 5000 } });
    expect(r.status).not.toBe('INVALID_INPUT');
  });

  it('returns INVALID_INPUT on negative/non-integer supply (spec §4.1)', () => {
    const b = createBoard(2);
    setState(b, { q: -1, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const bad = makeInventory([['air', -3]], DEFAULT_THRESHOLD);
    const r = solveWithValidation({ data, board: b, inventory: bad, budget: { maxNodes: 1000, maxTimeMs: 100 } });
    expect(r.status).toBe('INVALID_INPUT');
  });

  it('returns INVALID_INPUT when more than 8 anchors are placed (spec §5.1)', () => {
    const b = createBoard(5);
    const cells = [{q:0,r:0},{q:3,r:0},{q:-3,r:0},{q:0,r:3},{q:0,r:-3},{q:3,r:-3},{q:-3,r:3},{q:5,r:0},{q:-5,r:0}];
    const aspects = ['air','earth','fire','water','order','entropy','void','light','energy'];
    cells.forEach((c, i) => setState(b, c, { kind: 'ANCHOR', aspect: aspects[i]! }));
    const r = solveWithValidation({ data, board: b, inventory: inv, budget: { maxNodes: 1000, maxTimeMs: 100 } });
    expect(r.status).toBe('INVALID_INPUT');
  });
});
