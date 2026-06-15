export interface Hex {
  readonly q: number;
  readonly r: number;
}

export const HEX_DIRECTIONS: readonly Hex[] = [
  { q: 1, r: 0 }, { q: 1, r: -1 }, { q: 0, r: -1 },
  { q: -1, r: 0 }, { q: -1, r: 1 }, { q: 0, r: 1 },
];

export function hexKey(h: Hex): string {
  return `${h.q},${h.r}`;
}

export function parseHexKey(key: string): Hex {
  // Strict: exactly one comma, both sides signed integers. parseHexKey also parses external/untrusted
  // coords during board deserialization (spec §2.3), so malformed keys must fail loudly.
  const i = key.indexOf(',');
  if (i < 0 || i !== key.lastIndexOf(',')) throw new Error(`bad hex key: ${key}`);
  const qs = key.slice(0, i);
  const rs = key.slice(i + 1);
  if (!/^-?\d+$/.test(qs) || !/^-?\d+$/.test(rs)) throw new Error(`bad hex key: ${key}`);
  return { q: Number(qs), r: Number(rs) };
}

export function neighborsOf(h: Hex): Hex[] {
  return HEX_DIRECTIONS.map((d) => ({ q: h.q + d.q, r: h.r + d.r }));
}

export function distance(a: Hex, b: Hex): number {
  const dq = a.q - b.q;
  const dr = a.r - b.r;
  return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
}

export function isOnBoard(h: Hex, radius: number): boolean {
  return distance({ q: 0, r: 0 }, h) <= radius;
}

export function boardCells(radius: number): Hex[] {
  const cells: Hex[] = [];
  for (let q = -radius; q <= radius; q++) {
    const rLo = Math.max(-radius, -q - radius);
    const rHi = Math.min(radius, -q + radius);
    for (let r = rLo; r <= rHi; r++) cells.push({ q, r });
  }
  return cells;
}
