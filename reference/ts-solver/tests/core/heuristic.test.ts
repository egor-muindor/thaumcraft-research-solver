import { describe, expect, it } from 'bun:test';
import { buildAspectData } from '../../app/src/data/aspects';
import { makeInventory, DEFAULT_THRESHOLD } from '../../app/src/core/inventory';
import { createBoard, setState } from '../../app/src/core/board';
import { remainderHeuristic } from '../../app/src/core/heuristic';

const data = buildAspectData();

describe('remainderHeuristic (admissible, spec §5.2)', () => {
  it('is (0,0) when all anchors are already connected', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'PLACED', aspect: 'void', locked: false });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const inv = makeInventory([['air', 100], ['entropy', 100]], DEFAULT_THRESHOLD);
    const h = remainderHeuristic(data, b, inv);
    expect(h.cells).toBe(0);
    expect(h.scarcity).toBe(0);
  });

  it('needs >=1 inner cell for two anchors at distance 2 (hCells >= 1)', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    const inv = makeInventory([['air', 100]], DEFAULT_THRESHOLD);
    const h = remainderHeuristic(data, b, inv);
    expect(h.cells).toBeGreaterThanOrEqual(1);
  });

  it('routes around dead hexes (no path => Infinity scarcity)', () => {
    const b = createBoard(2);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 2, r: 0 }, { kind: 'ANCHOR', aspect: 'entropy' });
    // wall off (2,0) entirely with DEAD neighbors so it cannot be reached
    for (const n of [{ q: 1, r: 0 }, { q: 2, r: -1 }, { q: 1, r: 1 }]) setState(b, n, { kind: 'DEAD' });
    const inv = makeInventory([['air', 100]], DEFAULT_THRESHOLD);
    const h = remainderHeuristic(data, b, inv);
    expect(h.scarcity).toBe(Number.POSITIVE_INFINITY);
  });

  it('does not treat a locked-only island as a terminal (h stays finite/small)', () => {
    const b = createBoard(3);
    setState(b, { q: 0, r: 0 }, { kind: 'ANCHOR', aspect: 'air' });
    setState(b, { q: 1, r: 0 }, { kind: 'ANCHOR', aspect: 'fire' }); // air-fire? not an edge -> but heuristic ignores labels
    setState(b, { q: -3, r: 3 }, { kind: 'PLACED', aspect: 'water', locked: true }); // far island
    const inv = makeInventory([['air', 100]], DEFAULT_THRESHOLD);
    const h = remainderHeuristic(data, b, inv);
    // island must NOT force a long connection; with anchors adjacent, hCells should be 0
    expect(h.cells).toBe(0);
  });
});
