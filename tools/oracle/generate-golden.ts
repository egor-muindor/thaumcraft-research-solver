import { writeFileSync, mkdirSync } from "node:fs";
import { buildAspectData } from "../../reference/ts-solver/data/aspects";
import { makeInventory, DEFAULT_THRESHOLD } from "../../reference/ts-solver/core/inventory";
import { createBoard, setState } from "../../reference/ts-solver/core/board";
import { boardCells, hexKey } from "../../reference/ts-solver/core/hex";
import { solveWithValidation } from "../../reference/ts-solver/core/solver";

const data = buildAspectData();
const OUT = new URL("../../src/test/resources/golden/", import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

// 1) aspect-data fixture (so the Kotlin golden test runs on the EXACT same graph)
writeFileSync(OUT + "aspect-data.json", JSON.stringify({
  primals: [...data.primals],
  order: data.order,
  universe: [...data.universe],
  combinations: Object.fromEntries([...data.combinations].map(([k, v]) => [k, v])),
  translate: Object.fromEntries(data.translate),
}, null, 2));

type Cell = { key: string; state: "ANCHOR" | "DEAD" | "PLACED"; aspect?: string; locked?: boolean };
type Scenario = { name: string; radius: number; cells: Cell[]; inv: "rich" | Record<string, number>; threshold?: number; maxNodes: number };

const rich = (d = data) => makeInventory([...d.universe].map((a) => [a, 100] as [string, number]), DEFAULT_THRESHOLD);
const fixedClock = () => 0;
const HUGE_TIME = 3_600_000;

// Helper: mark every board cell DEAD except those in keepKeys; returns a fresh board with those cells empty.
const deadExcept = (radius: number, keepKeys: string[]): Cell[] =>
  [...boardCells(radius)]
    .filter((h) => !keepKeys.includes(hexKey(h)))
    .map((h) => ({ key: hexKey(h), state: "DEAD" as const }));

const scenarios: Scenario[] = [
  { name: "r2_air_entropy_bridge", radius: 2, cells: [
      { key: "0,0", state: "ANCHOR", aspect: "air" }, { key: "2,0", state: "ANCHOR", aspect: "entropy" } ], inv: "rich", maxNodes: 2_000_000 },
  { name: "r2_two_anchor", radius: 2, cells: [
      { key: "-1,0", state: "ANCHOR", aspect: "air" }, { key: "1,0", state: "ANCHOR", aspect: "entropy" } ], inv: "rich", maxNodes: 2_000_000 },
  { name: "r2_dead_center", radius: 2, cells: [
      { key: "-1,0", state: "ANCHOR", aspect: "air" }, { key: "1,0", state: "ANCHOR", aspect: "entropy" }, { key: "0,0", state: "DEAD" } ], inv: "rich", maxNodes: 2_000_000 },
  { name: "r3_three_anchor", radius: 3, cells: [
      { key: "-2,0", state: "ANCHOR", aspect: "air" }, { key: "2,0", state: "ANCHOR", aspect: "entropy" }, { key: "0,2", state: "ANCHOR", aspect: "fire" } ], inv: "rich", maxNodes: 4_000_000 },
  { name: "r2_void_zero_supply", radius: 2, cells: [
      { key: "-1,0", state: "ANCHOR", aspect: "air" }, { key: "1,0", state: "ANCHOR", aspect: "entropy" } ],
    inv: Object.fromEntries([...data.universe].map((a) => [a, a === "void" ? 0 : 100])), maxNodes: 2_000_000 },
  // Exercises INFEASIBLE_INVENTORY: the only valid intermediate (void, bridging air↔entropy) cannot be
  // obtained or crafted (both void and its components air+entropy are at supply 0, and air+entropy are
  // primals so they cannot be crafted). Dead cells reduce the search space so the solver exhausts it.
  { name: "r2_infeasible_void_bridge", radius: 2,
    cells: [
      { key: "0,0", state: "ANCHOR", aspect: "air" },
      { key: "2,0", state: "ANCHOR", aspect: "entropy" },
      ...deadExcept(2, ["0,0", "1,0", "2,0"]),
    ],
    inv: Object.fromEntries([...data.universe].map((a) => [a, 0])),
    maxNodes: 200_000 },
  // Exercises positive scarcity: same tiny board but void=1 (below threshold=50), so
  // directPenalty(void)=1+K*(50-1)=50. Air and entropy are primals at supply 0, so
  // craftCost(void)=∞; obtainCost(void)=min(50,∞)=50. Status OPTIMAL, scarcity=50.
  { name: "r2_scarce_void_bridge", radius: 2,
    cells: [
      { key: "0,0", state: "ANCHOR", aspect: "air" },
      { key: "2,0", state: "ANCHOR", aspect: "entropy" },
      ...deadExcept(2, ["0,0", "1,0", "2,0"]),
    ],
    inv: Object.fromEntries([...data.universe].map((a) => [a, a === "void" ? 1 : 0])),
    maxNodes: 200_000 },
];

const ACCEPTABLE_STATUSES = new Set(["OPTIMAL", "INFEASIBLE_INVENTORY", "UNSAT_PROVEN"]);

const results: Record<string, { status: string; scarcity: number | "inf"; cells: number | null }> = {};
for (const sc of scenarios) {
  const b = createBoard(sc.radius);
  for (const c of sc.cells) {
    const [q, r] = c.key.split(",").map(Number);
    if (c.state === "ANCHOR") setState(b, { q, r }, { kind: "ANCHOR", aspect: c.aspect! });
    else if (c.state === "DEAD") setState(b, { q, r }, { kind: "DEAD" });
    else setState(b, { q, r }, { kind: "PLACED", aspect: c.aspect!, locked: c.locked ?? false });
  }
  const inv = sc.inv === "rich" ? rich() : makeInventory(Object.entries(sc.inv as Record<string, number>), sc.threshold ?? DEFAULT_THRESHOLD);
  const res = solveWithValidation({ data, board: b, inventory: inv, budget: { maxNodes: sc.maxNodes, maxTimeMs: HUGE_TIME }, now: fixedClock });
  if (!ACCEPTABLE_STATUSES.has(res.status)) {
    throw new Error(`Scenario '${sc.name}' produced status '${res.status}' — not acceptable as golden truth. Fix the scenario or raise its budget.`);
  }
  results[sc.name] = {
    status: res.status,
    scarcity: res.cost ? (Number.isFinite(res.cost.scarcity) ? res.cost.scarcity : "inf") : "inf",
    cells: res.cost ? res.cost.cells : null,
  };
}

// Sanity check: scenarios.json written by the TS oracle only; the Kotlin replay must reproduce these exact results.
// If a future run yields TIMEOUT or UNKNOWN for any scenario, the throw above will catch it.
writeFileSync(OUT + "scenarios.json", JSON.stringify({ scenarios, results }, null, 2));
console.log("wrote", Object.keys(results).length, "scenarios");
