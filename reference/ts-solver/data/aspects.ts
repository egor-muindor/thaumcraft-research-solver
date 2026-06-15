import { PRIMALS, COMBINATIONS_4_2_2_0, ADDONS, TRANSLATE } from './raw';

export type Aspect = string;

export interface AspectData {
  readonly primals: ReadonlySet<Aspect>;
  readonly combinations: ReadonlyMap<Aspect, readonly [Aspect, Aspect]>;
  readonly universe: ReadonlySet<Aspect>;
  readonly translate: ReadonlyMap<Aspect, string>;
  readonly adjacency: ReadonlyMap<Aspect, ReadonlySet<Aspect>>;
  /** Universe in Thaumcraft registration order: primals first, then compounds by mod tier order. */
  readonly order: readonly Aspect[];
}

export class AspectDataError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AspectDataError';
  }
}

export interface BuildOptions {
  /** Default '4.2.2.0' — only that version is ported. */
  version?: '4.2.2.0';
  /** Default ['fm','mb','gt','tb','av'] (every Thaumcraft addon in GTNH that registers aspects). */
  addons?: readonly string[];
  /** Test-only: replace the entire combinations map (bypasses version/addons). */
  overrideCombinations?: Record<string, [Aspect, Aspect]>;
  /** Test-only: supply latin names for synthetic aspects (required: override-mode aspects have no TRANSLATE entry). */
  overrideTranslate?: Record<string, string>;
  /** Test-only: aspects that join the universe even without a recipe (simulates an addon `aspects[]` entry that is component-only, spec §2.1). */
  overrideDeclaredAspects?: readonly string[];
}

export function buildAspectData(opts: BuildOptions = {}): AspectData {
  const primals = new Set<Aspect>(PRIMALS);

  const combos = new Map<Aspect, readonly [Aspect, Aspect]>();
  // Spec §2.1: the universe explicitly unions addon-declared aspects, not only recipe keys/components.
  const declared = new Set<Aspect>();
  if (opts.overrideCombinations) {
    for (const [k, v] of Object.entries(opts.overrideCombinations)) combos.set(k, v);
    for (const a of opts.overrideDeclaredAspects ?? []) declared.add(a);
  } else {
    for (const [k, v] of Object.entries(COMBINATIONS_4_2_2_0)) combos.set(k, v);
    for (const id of opts.addons ?? ['fm', 'mb', 'gt', 'tb', 'av']) {
      const addon = ADDONS[id];
      if (!addon) throw new AspectDataError(`unknown addon '${id}'`);
      for (const a of addon.aspects) declared.add(a);
      for (const [k, v] of Object.entries(addon.combinations)) combos.set(k, v);
    }
  }

  // Universe = primals ∪ declared addon aspects ∪ compound keys ∪ all components (spec §2.1).
  const universe = new Set<Aspect>(primals);
  for (const a of declared) universe.add(a);
  for (const [k, [c1, c2]] of combos) {
    universe.add(k);
    universe.add(c1);
    universe.add(c2);
  }

  // No self-reference; components must be defined (primal or compound key).
  for (const [k, [c1, c2]] of combos) {
    if (c1 === k || c2 === k) throw new AspectDataError(`aspect '${k}' references itself`);
    for (const c of [c1, c2]) {
      if (!primals.has(c) && !combos.has(c)) {
        throw new AspectDataError(`component '${c}' of '${k}' is not defined`);
      }
    }
  }

  // Acyclicity of the "is-component-of" DAG (compound depends on its components).
  detectCycle(combos, primals);

  // translate for every universe member. In override (test) mode, callers may supply
  // overrideTranslate for synthetic aspects; any aspect still missing a translation throws.
  const translate = new Map<Aspect, string>();
  for (const a of universe) {
    const latin = TRANSLATE[a] ?? opts.overrideTranslate?.[a];
    if (!latin) throw new AspectDataError(`aspect '${a}' has no translation/icon mapping`);
    translate.set(a, latin);
  }

  // Undirected adjacency (deduped Set): edge compound–component (spec §2.1).
  const adjacency = new Map<Aspect, Set<Aspect>>();
  const link = (a: Aspect, b: Aspect) => {
    (adjacency.get(a) ?? adjacency.set(a, new Set()).get(a)!).add(b);
    (adjacency.get(b) ?? adjacency.set(b, new Set()).get(b)!).add(a);
  };
  for (const a of universe) if (!adjacency.has(a)) adjacency.set(a, new Set());
  for (const [k, [c1, c2]] of combos) {
    link(k, c1);
    link(k, c2);
  }

  // Display order: primals (PRIMALS order), then compounds in registration/tier order
  // (base Thaumcraft decl order, then addons), then any declared-only aspect as a fallback.
  const order: Aspect[] = [...primals, ...combos.keys()];
  const seen = new Set<Aspect>(order);
  for (const a of universe) if (!seen.has(a)) { order.push(a); seen.add(a); }

  return { primals, combinations: combos, universe, translate, adjacency, order };
}

function detectCycle(combos: ReadonlyMap<Aspect, readonly [Aspect, Aspect]>, primals: ReadonlySet<Aspect>): void {
  const state = new Map<Aspect, 0 | 1 | 2>(); // 0=unseen,1=in-stack,2=done
  const visit = (a: Aspect): void => {
    if (primals.has(a)) return;
    const s = state.get(a) ?? 0;
    if (s === 2) return;
    if (s === 1) throw new AspectDataError(`cycle detected through aspect '${a}'`);
    state.set(a, 1);
    const recipe = combos.get(a);
    if (recipe) for (const c of recipe) visit(c);
    state.set(a, 2);
  };
  for (const a of combos.keys()) visit(a);
}

export function iconLatin(data: AspectData, a: Aspect): string {
  const latin = data.translate.get(a);
  if (!latin) throw new AspectDataError(`no icon for '${a}'`);
  return latin;
}

/** Base-relative icon URL for the browser (resolved under import.meta.env.BASE_URL by callers). */
export function iconFile(data: AspectData, a: Aspect): string {
  return `aspects/color/${iconLatin(data, a)}.png`;
}
