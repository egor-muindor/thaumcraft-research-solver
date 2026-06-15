import type { Aspect, AspectData } from '../data/aspects';

export function neighbors(data: AspectData, a: Aspect): ReadonlySet<Aspect> {
  // Defensive copy: data.adjacency holds live backing Sets and ReadonlySet is compile-time only,
  // so hand out a copy to stop a (casting) caller corrupting the shared graph.
  const backing = data.adjacency.get(a);
  return backing ? new Set(backing) : new Set<Aspect>();
}

export function isValidLink(data: AspectData, a: Aspect, b: Aspect): boolean {
  if (a === b) return false;
  // Hot path (called per candidate in the solver): read the backing set directly, no per-call copy.
  return data.adjacency.get(a)?.has(b) ?? false;
}

/** Direct multiplicity of component `x` in the recipe of `y` (0, 1, or 2). */
export function mult(data: AspectData, x: Aspect, y: Aspect): number {
  const recipe = data.combinations.get(y);
  if (!recipe) return 0;
  return (recipe[0] === x ? 1 : 0) + (recipe[1] === x ? 1 : 0);
}

const primalVecCache = new WeakMap<AspectData, Map<Aspect, ReadonlyMap<Aspect, number>>>();

export function primalVec(data: AspectData, a: Aspect): ReadonlyMap<Aspect, number> {
  let cache = primalVecCache.get(data);
  if (!cache) {
    cache = new Map();
    primalVecCache.set(data, cache);
  }
  const cached = cache.get(a);
  if (cached) return new Map(cached);

  let result: Map<Aspect, number>;
  if (data.primals.has(a)) {
    result = new Map([[a, 1]]);
  } else {
    const recipe = data.combinations.get(a);
    if (!recipe) throw new Error(`aspect '${a}' is neither primal nor a compound`);
    result = new Map();
    for (const c of recipe) {
      for (const [p, n] of primalVec(data, c)) {
        result.set(p, (result.get(p) ?? 0) + n);
      }
    }
  }
  cache.set(a, result);
  return new Map(result);
}
