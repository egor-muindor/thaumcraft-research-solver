import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { makeInventory, DEFAULT_THRESHOLD } from '../../app/src/core/inventory';
import { createBoard, setState, validate } from '../../app/src/core/board';
import { solve } from '../../app/src/core/solver';

const data = buildAspectData();
const rich = makeInventory([...data.universe].map((a) => [a, 100] as [string, number]), DEFAULT_THRESHOLD);

// (a) VERBATIM from the plan Task 6.7 Step 1 test:
// seed:true must still yield the same OPTIMAL cells:1 on the 2-anchor R2 board.
describe('seed (spec §5.3) is untrusted and optimum-preserving', () => {
  it('produces the same OPTIMAL cost with seeding enabled as the exhaustive search', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const r = solve({ data, board: b, inventory: rich, budget: { maxNodes: 2_000_000, maxTimeMs: 20_000 }, seed: true });
    expect(r.status).toBe('OPTIMAL');
    expect(r.cost?.cells).toBe(1); // still the 1-cell optimum
    expect(r.board && validate(data, r.board).valid).toBe(true);
  });
});

// (b) Seed yields a valid connected incumbent on the hard R5 4-anchor board.
// With maxNodes:1 the DFS truncates after ~1 node, so the ONLY possible incumbent is the seed.
// A defined+valid board proves the seed found a feasible board.
describe('seed anytime behavior on hard boards', () => {
  it('seed yields a valid connected incumbent on a hard R5 4-anchor board (anytime)', () => {
    const b = createBoard(5);
    setState(b, { q: -4, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 4, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    setState(b, { q: 0, r: 4 }, { kind: 'ANCHOR', aspect: 'fire' });
    setState(b, { q: 0, r: -4 }, { kind: 'ANCHOR', aspect: 'water' });
    const r = solve({ data, board: b, inventory: rich, budget: { maxNodes: 1, maxTimeMs: 3000 }, seed: true });
    expect(r.board).toBeDefined();
    expect(validate(data, r.board!).valid).toBe(true);
  });

  // (c) Same for the R4 4-anchor board.
  it('seed yields a valid connected incumbent on a hard R4 4-anchor board (anytime)', () => {
    const b = createBoard(4);
    setState(b, { q: -3, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 3, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    setState(b, { q: 0, r: 3 }, { kind: 'ANCHOR', aspect: 'fire' });
    setState(b, { q: 0, r: -3 }, { kind: 'ANCHOR', aspect: 'water' });
    const r = solve({ data, board: b, inventory: rich, budget: { maxNodes: 1, maxTimeMs: 3000 }, seed: true });
    expect(r.board).toBeDefined();
    expect(validate(data, r.board!).valid).toBe(true);
  });

  // (d) Regression: seeding does not corrupt the proven optimum.
  // This is already covered by test (a) above — seed:true with full budget yields OPTIMAL cells:1
  // on the 2-anchor R2 board (same result as seed:false). If the seed were to set a suboptimal
  // incumbent that blocked improvement, the DFS would still beat it and reach cells:1.
  // Documented here for traceability rather than duplicating the test.
  it('seed does not degrade the proven optimum on R2 (already covered by (a), explicit regression)', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    // seed:false baseline
    const withoutSeed = solve({ data, board: b, inventory: rich, budget: { maxNodes: 2_000_000, maxTimeMs: 20_000 }, seed: false });
    // seed:true must agree
    const b2 = createBoard(2);
    setState(b2, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b2, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const withSeed = solve({ data, board: b2, inventory: rich, budget: { maxNodes: 2_000_000, maxTimeMs: 20_000 }, seed: true });
    expect(withoutSeed.status).toBe('OPTIMAL');
    expect(withSeed.status).toBe('OPTIMAL');
    expect(withSeed.cost?.cells).toBe(withoutSeed.cost?.cells);
  });
});
