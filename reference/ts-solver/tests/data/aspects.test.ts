import { describe, expect, it } from 'bun:test';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildAspectData, iconLatin, AspectDataError } from '../../app/src/data/aspects';

// Canonical undirected edge set (each edge endpoints sorted, list sorted). Extracted directly
// from the GTNH 2.8.4 mod jars by scripts/extract-aspects.mjs; frozen as the normative fixture
// (spec §2.1). Covers base Thaumcraft 4.2.3.5a + fm/mb/gt/tb/av addon aspects.
const EXPECTED_EDGES = [
  'aequalitas--mind','aequalitas--order','air--aura','air--flight','air--light','air--motion','air--senses','air--tree',
  'air--void','air--weather','armor--earth','armor--tabernus','armor--tool','astrum--light','astrum--primordium','aura--magic',
  'beast--cloth','beast--flesh','beast--life','beast--man','beast--motion','caelum--crystal','caelum--metal','cheatiness--greed',
  'cheatiness--mine','cloth--tool','cold--entropy','cold--fire','craft--man','craft--tool','crop--harvest','crop--man',
  'crop--plant','crystal--earth','crystal--metal','crystal--order','darkness--eldritch','darkness--light','darkness--void','death--entropy',
  'death--flesh','death--life','death--soul','death--undead','earth--life','earth--metal','earth--mine','earth--plant',
  'earth--travel','eldritch--terminus','eldritch--void','electricity--energy','electricity--mechanism','energy--fire','energy--magic','energy--order',
  'energy--radioactivity','entropy--exchange','entropy--poison','entropy--stupidity','entropy--taint','entropy--trap','entropy--void','envy--hunger',
  'envy--senses','exchange--order','fire--light','fire--mind','fire--nether','fire--weapon','fire--wrath','flesh--lust',
  'flight--motion','flight--pride','gloria--man','gloria--travel','gluttony--hunger','gluttony--void','greed--hunger','greed--man',
  'greed--terminus','harvest--tool','heal--life','heal--order','hunger--life','hunger--lust','hunger--void','life--plant',
  'life--slime','life--soul','life--water','light--radioactivity','magic--nether','magic--taint','magic--void','magnetism--metal',
  'magnetism--travel','man--mind','man--mine','man--tool','mechanism--motion','mechanism--tool','mind--soul','mind--stupidity',
  'mind--vesania','motion--order','motion--primordium','motion--trap','motion--travel','motion--undead','order--time','order--tool',
  'plant--tree','poison--water','pride--void','primordium--void','senses--soul','slime--water','sloth--soul','sloth--trap',
  'tabernus--travel','taint--vesania','time--void','tool--weapon','water--weather','weapon--wrath',
];

describe('buildAspectData (defaults: TC 4.2.3.5a + fm/mb/gt/tb/av)', () => {
  const data = buildAspectData();

  it('has 69 aspects in the universe', () => {
    expect(data.universe.size).toBe(69);
  });

  it('has exactly 6 primals with no combinations', () => {
    expect(data.primals.size).toBe(6);
    for (const p of data.primals) expect(data.combinations.has(p)).toBe(false);
  });

  it('builds exactly the canonical normative edge set (126 undirected edges)', () => {
    const edges = new Set<string>();
    for (const [a, nbrs] of data.adjacency) for (const b of nbrs) edges.add([a, b].sort().join('--'));
    const sorted = [...edges].sort();
    // Frozen reference edge set for TC 4.2.3.5a + fm/mb/gt/tb/av (spec §2.1). Detects swapped/missing edges, not just count.
    expect(sorted).toEqual(EXPECTED_EDGES);
    expect(sorted).toHaveLength(126);
  });

  it('connects compound to each direct component (undirected)', () => {
    // magic = void + energy
    expect(data.adjacency.get('magic')!.has('void')).toBe(true);
    expect(data.adjacency.get('void')!.has('magic')).toBe(true);
    expect(data.adjacency.get('magic')!.has('energy')).toBe(true);
    // addon: electricity = energy + mechanism
    expect(data.adjacency.get('electricity')!.has('energy')).toBe(true);
    expect(data.adjacency.get('mechanism')!.has('electricity')).toBe(true);
  });

  it('does NOT connect siblings (shared parent is not an edge)', () => {
    // light = air+fire, energy = order+fire: both children of fire, but not linked to each other
    expect(data.adjacency.get('light')?.has('energy') ?? false).toBe(false);
    // primals are not linked to each other
    expect(data.adjacency.get('air')?.has('earth') ?? false).toBe(false);
  });

  it('includes every recipe component in the universe', () => {
    for (const [, [c1, c2]] of data.combinations) {
      expect(data.universe.has(c1)).toBe(true);
      expect(data.universe.has(c2)).toBe(true);
    }
  });

  it('has a latin translation and an existing color icon for every aspect', () => {
    const root = resolve(import.meta.dir, '../..');
    for (const a of data.universe) {
      const latin = iconLatin(data, a);
      expect(typeof latin).toBe('string');
      expect(existsSync(resolve(root, 'aspects/color', `${latin}.png`))).toBe(true);
    }
  });

  it('includes a declared addon-style aspect that has no recipe of its own (spec §2.1 union)', () => {
    const d = buildAspectData({
      overrideCombinations: { foo: ['air', 'fire'] },
      overrideDeclaredAspects: ['standalone'],
      overrideTranslate: { foo: 'foo', standalone: 'standalone' },
      addons: [],
    });
    expect(d.universe.has('standalone')).toBe(true);
  });
});

describe('GTNH 2.8.4 aspect additions (extracted from mods)', () => {
  const data = buildAspectData();

  // GregTech custom aspects + Thaumic Boots + Avaritia — missing from the original
  // ythri (TC 4.2.2.0) port. Each entry: key -> sorted [componentA, componentB].
  const NEW_ASPECTS: Record<string, [string, string]> = {
    gloria: ['man', 'travel'],
    aequalitas: ['mind', 'order'],
    vesania: ['mind', 'taint'],
    primordium: ['motion', 'void'],
    astrum: ['light', 'primordium'],
    tabernus: ['armor', 'travel'],
    caelum: ['crystal', 'metal'],
    terminus: ['eldritch', 'greed'],
  };

  it('registers every new aspect with its mod-defined components and icon', () => {
    for (const [key, comps] of Object.entries(NEW_ASPECTS)) {
      expect(data.universe.has(key)).toBe(true);
      expect([...data.combinations.get(key)!].sort()).toEqual(comps);
      expect(iconLatin(data, key)).toBe(key); // new aspects keyed by their latin tag
    }
  });

  it('fixes the strontio icon (was the "stronito" typo in the ythri port)', () => {
    expect(iconLatin(data, 'stupidity')).toBe('strontio');
  });

  it('drops the stale stone/seed aspects absent from TC 4.2.3.5a', () => {
    expect(data.universe.has('stone')).toBe(false);
    expect(data.universe.has('seed')).toBe(false);
    expect(data.translate.has('stone')).toBe(false);
    expect(data.translate.has('seed')).toBe(false);
  });

  it('keeps astrum -> primordium (a compound built on another addon aspect)', () => {
    expect(data.adjacency.get('astrum')!.has('primordium')).toBe(true);
    expect(data.adjacency.get('primordium')!.has('astrum')).toBe(true);
  });
});

describe('aspect display order (Thaumcraft registration order, not alphabetical)', () => {
  const data = buildAspectData();

  it('covers the whole universe exactly once', () => {
    expect(data.order).toHaveLength(data.universe.size);
    expect(new Set(data.order)).toEqual(new Set(data.universe));
  });

  it('lists the 6 primals first, in Thaumcraft declaration order', () => {
    expect(data.order.slice(0, 6)).toEqual(['air', 'earth', 'fire', 'water', 'order', 'entropy']);
  });

  it('orders by mod tier, not alphabetically', () => {
    const idx = (a: string) => data.order.indexOf(a);
    // base compound (void) registered before any GregTech addon aspect
    expect(idx('void')).toBeLessThan(idx('gloria'));
    expect(idx('terminus')).toBeGreaterThan(idx('void'));
    // 'void' precedes 'aura' here — something alphabetical order would never do
    expect(idx('void')).toBeLessThan(idx('aura'));
  });
});

describe('startup validation (fail loudly)', () => {
  it('throws AspectDataError naming the aspect on a self-referential recipe', () => {
    expect(() =>
      buildAspectData({ overrideCombinations: { foo: ['foo', 'air'] }, addons: [] }),
    ).toThrow(/foo/);
  });

  it('throws on a cycle (a<-b<-a)', () => {
    expect(() =>
      buildAspectData({ overrideCombinations: { acomp: ['bcomp', 'air'], bcomp: ['acomp', 'fire'] }, addons: [] }),
    ).toThrow(AspectDataError);
  });

  it('throws when a component is undefined (not primal, not a compound key)', () => {
    expect(() =>
      buildAspectData({ overrideCombinations: { x: ['air', 'doesnotexist'] }, addons: [] }),
    ).toThrow(/doesnotexist/);
  });

  it('throws when an aspect lacks a translation', () => {
    expect(() =>
      buildAspectData({ overrideCombinations: { untranslated: ['air', 'fire'] }, addons: [] }),
    ).toThrow(/untranslated/);
  });
});
