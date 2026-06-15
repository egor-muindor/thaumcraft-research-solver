# Thaumcraft Research Solver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A client-side GTNH 2.8.4 Kotlin addon that adds a "Solve" button to the Thaumcraft research table, computes the optimal aspect layout for the current note off-thread, previews it as ghosts, and on confirmation auto-fills the hex grid by driving Thaumcraft's own network packets.

**Architecture:** Three layers (hexagonal, mirroring ThaumcraftResearchTweaks). (1) `solver` — a pure, Minecraft-free Kotlin port of the existing TS branch-and-bound solver (objective: lexicographic `scarcity → cells`), unit-tested on the JVM with golden cross-checks against the TS oracle. (2) `integration` — adapters that read live note/pool/ink state from Thaumcraft and apply solutions via TC packets. (3) `ui` — a Mixin into ResearchTweaks' `ComposableContainerGui` that injects a button, a progress spinner, and a ghost overlay, plus a client-thread state machine.

**Tech Stack:** Kotlin (Forgelin), RetroFuturaGradle + GTNHGradle convention plugin, UniMixins (SpongeMixins), Thaumcraft 1.7.10 4.2.3.5a, ThaumcraftResearchTweaks 1.3.0, JUnit 5 + Gson (test only), MC 1.7.10 / Forge. Dev JDK 17 (Gradle/RFG), runtime targets MC 1.7.10 via lwjgl3ify ForgePatches.

---

## How to read this plan (conventions)

The `solver` layer is a **file-for-file port** of `reference/ts-solver/core/*` (read-only source of truth). Port tasks name the exact TS source file and the exact Kotlin destination. Where a mechanical translation is error-prone, the **full Kotlin is inlined**; where it is straightforward, the task says "translate the rest from the named TS file applying gotchas G1–G11" — the executor has the TS open and the tests below pin correctness (TDD).

The `integration` and `ui` layers depend on exact internal signatures of Thaumcraft + ThaumcraftResearchTweaks that must be **confirmed against the decompiled jars at implementation time** (`reference/jars/` — git-ignored, present locally). Those phases therefore begin with concrete *investigation* tasks (run `javap` / decompile, record the signature) before the implementation tasks. Public TC packet constructors are already known (see `reference/thaumcraft-integration.md`) and are inlined.

### TS → Kotlin porting gotchas (G1–G11) — referenced by every solver task

- **G1 — `Aspect` is a `String`.** `typealias Aspect = String` in the solver; the string is the Thaumcraft tag (e.g. `"ignis"`). The solver stays string-based to match the oracle byte-for-byte. The integration layer converts TC `Aspect` objects ↔ tags at the boundary.
- **G2 — Preserve insertion order.** TS `Map`/`Set` iterate in insertion order, and the solver's candidate enumeration (`for (a of data.universe)`) + frontier scan (`for ([k,s] of work.cells)`) depend on it for tie-breaking. Use `LinkedHashMap`/`LinkedHashSet` **everywhere** the TS uses `Map`/`Set`. Golden parity depends on this.
- **G3 — `Cost` holds two `Double`s.** `scarcity` and `cells` are both `Double` so they can carry `+Infinity` (the heuristic returns `cells = +Inf`; `addCost`/`compareCost` must keep matching TS). The integer cell count reported to the UI comes from `placements.size`, not from `Cost.cells`.
- **G4 — Infinity.** `Number.POSITIVE_INFINITY` → `Double.POSITIVE_INFINITY`; `Number.isFinite(x)` → `x.isFinite()`; `Number.isInteger(n)` → integer-typed values or an explicit check.
- **G5 — Stable sort.** Kotlin's `sortedWith`/`sortWith` is stable (TimSort). Build the candidate list in `data.universe` order, then `sortWith(compareBy { obtainCost(...) })` — but compare `Double`s with the same infinity-safe logic as the TS (`cx == cy -> 0; cx < cy -> -1; else 1`), never by subtraction.
- **G6 — hexKey string compare.** `nk < bestK` in TS is JS string `<` (code-unit order). Kotlin `String` `<` (compareTo) is identical for the ASCII set `[-0-9,]` that hex keys use.
- **G7 — Caches, not WeakMaps.** Replace `WeakMap`/`WeakMap`-nesting with plain `HashMap` scoped to a single solve (or to the `AspectData`/`Inventory` instance). No GC semantics are needed within one solve. `obtainCost` memo = `HashMap<Aspect, Double>`.
- **G8 — Recursion depth.** Solver DFS depth ≤ cell count (≤ 91); allocate DP depth ≤ aspect-closure size. Default JVM stack is sufficient; no trampolining needed.
- **G9 — Typed arrays.** `Float64Array` → `DoubleArray`; `Uint8Array` → `BooleanArray`. Bit ops (`<<`, `&`, `^`, `(s-1)&mask`) are identical on Kotlin `Int`.
- **G10 — Errors.** `throw new Error(msg)` → `throw IllegalArgumentException(msg)` (validation) or `IllegalStateException(msg)` (invariants). Tests assert the throw, not the message text.
- **G11 — Absent map keys.** `map.get(k) ?? default` → `map[k] ?: default`. Absent supply key ⇒ `0`.

---

## File Structure

```
settings.gradle                              # GTNH settings convention plugin
build.gradle                                 # GTNH build convention plugin (+ kotlin, +junit)
gradle.properties                            # modId/modGroup/mixins/deps config (single source of truth)
gradle/wrapper/…                             # Gradle wrapper (8.x)
.gitignore                                   # (extend existing)

src/main/kotlin/io/github/muindor/tcresearchsolver/
  TcResearchSolverMod.kt                     # @Mod entry point (modid "tcresearchsolver")
  Tags.kt                                    # generated-style constants: MODID, VERSION
  config/ Config.kt                          # Forge config: maxSolveMs, previewConfirm, keybind
  solver/                                    # PURE — no Minecraft imports
    Hex.kt                                   # port of core/hex.ts
    Cost.kt                                  # port of core/cost.ts
    AspectData.kt                            # port of data/aspects.ts (types + buildAspectData)
    Raw.kt                                   # port of data/raw.ts (static tables; oracle/golden only)
    AspectGraph.kt                           # port of core/aspectGraph.ts
    Inventory.kt                             # port of core/inventory.ts (incl. allocate DP)
    Steiner.kt                               # port of core/steiner.ts
    Board.kt                                 # port of core/board.ts
    Heuristic.kt                             # port of core/heuristic.ts
    Solver.kt                                # port of core/solver.ts (solve + solveWithValidation)
  integration/
    AspectDataProvider.kt                    # build AspectData live from Aspect.aspects/getComponents()
    BoardReader.kt                           # ResearchNoteData -> solver Board
    InventoryReader.kt                       # AspectPool -> solver Inventory
    Applier.kt                               # combine missing compounds + place via packets; post-verify
    TcTypes.kt                               # thin reflective/typed accessors for TC + RT internals
  ui/
    SolveController.kt                       # client-thread state machine (Idle/Solving/Preview/Applying/Done/Error)
    SolveWorker.kt                           # background thread runner around Solver
    SolveButtonUIComponent.kt               # clickable button (mirrors CopyButtonUIComponent)
    SpinnerComponent.kt                      # progress text/spinner (TickingUIComponent)
    GhostOverlayComponent.kt                 # ForegroundUIComponent ghost preview
  mixin/
    ResearchTableGuiFactoryMixin.kt          # appends our components; captures layout/tile/container/player
    Plugin.kt                                # IFMLLoadingPlugin / mixin config wiring if needed

src/main/resources/
  mcmod.info
  mixins.tcresearchsolver.json
  pack.mcmeta (if required by template)

src/test/kotlin/io/github/muindor/tcresearchsolver/solver/
  HexTest.kt CostTest.kt AspectGraphTest.kt InventoryCostTest.kt InventoryAllocateTest.kt
  SteinerTest.kt BoardModelTest.kt BoardValidateTest.kt BoardSerializeTest.kt
  HeuristicTest.kt SolverTest.kt SolverPrevalidateTest.kt SolverSeedTest.kt AspectDataTest.kt
  GoldenSolverTest.kt                        # cross-check vs TS oracle fixtures
src/test/resources/golden/
  aspect-data.json                           # oracle dump of universe/combinations/primals/adjacency
  scenarios.json                             # oracle dump of {scenario -> status,scarcity,cells}

tools/oracle/
  generate-golden.ts                         # bun script: imports reference/ts-solver, emits golden fixtures
  package.json                               # bun deps (none beyond TS); run via `bun run`
```

---

# Phase 1 — GTNH Gradle/Kotlin scaffolding (runnable empty mod)

**Exit criteria:** `./gradlew build` produces a jar; `./gradlew test` runs a trivial Kotlin JUnit 5 test; `./gradlew runClient` launches the GTNH dev client with our mod, Thaumcraft, and ThaumcraftResearchTweaks loaded (manual check).

### Task 1.1: Select and pin the dev JDK

**Files:**
- Create: `.sdkmanrc` (optional, documents the JDK)

GTNHGradle/RetroFuturaGradle for the 2.8.4 era requires **JDK 17** to run Gradle (Java 25, the only JDK on PATH, is too new for the RFG/Gradle 8.x toolchain and will fail). The MC 1.7.10 bytecode is produced via the buildscript's managed toolchain.

- [ ] **Step 1: Install/locate a JDK 17**

Run (whichever is available):
```bash
sdk install java 17.0.11-tem || brew install --cask temurin@17
/usr/libexec/java_home -V   # list installed JDKs; note the 17.x path
```
Expected: a Temurin/Adoptium 17.x JDK present.

- [ ] **Step 2: Record it for the project**

Create `.sdkmanrc`:
```
java=17.0.11-tem
```
Also set it for the current shell so later steps use it:
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
java -version   # must print 17.x
```
Expected: `openjdk version "17...`.

- [ ] **Step 3: Commit**

```bash
git add .sdkmanrc
git commit -m "chore: pin dev JDK 17 for GTNH buildscript"
```

### Task 1.2: Lay down the GTNH buildscript skeleton

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`

Use the GTNH ExampleMod 1.7.10 convention (the canonical 2.8.4-era template). The convention plugins live on the GTNH maven and are pulled by the wrapper.

- [ ] **Step 1: `settings.gradle`**

```groovy
pluginManagement {
    repositories {
        maven { url = uri("https://nexus.gtnewhorizons.com/repository/public/") }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}
plugins {
    id "com.gtnewhorizons.gtnhsettingsconvention" version "1.0.36"
}
```
> Confirm the latest `gtnhsettingsconvention` version against `https://nexus.gtnewhorizons.com` at implementation time; the convention pins a compatible `gtnhconvention` for `build.gradle`.

- [ ] **Step 2: `build.gradle`**

```groovy
plugins {
    id "com.gtnewhorizons.gtnhconvention"
}
```
(All real configuration lives in `gradle.properties`; the convention plugin wires RFG, mixins, Kotlin if enabled, deobf deps, and run tasks.)

- [ ] **Step 3: `gradle.properties` — identity + features**

```properties
modName = Thaumcraft Research Solver
modId = tcresearchsolver
modGroup = io.github.muindor.tcresearchsolver

# Kotlin (Forgelin) — provided by GTNH at runtime; enable the source set
enableModernJavaSyntax = false
usesMixins = true
usesMixinDebug = false
mixinPlugin =
mixinsPackage = io.github.muindor.tcresearchsolver.mixin
coreModClass =
containsMixinsAndOrCoreModOnly = false

# Identity for mcmod.info / @Mod
apiPackage =
accessTransformersFile =
usesShadowedDependencies = false

# Misc GTNH flags (leave template defaults unless a later task changes them)
generateGradleTokenClass = io.github.muindor.tcresearchsolver.Tags
gradleTokenModId = MODID
gradleTokenModName = MODNAME
gradleTokenVersion = VERSION
```
> The exact set of recognized keys is defined by the convention version chosen in Step 1. Start from the upstream ExampleMod `gradle.properties`, then overwrite the identity keys above. Leave unknown template keys at their defaults.

- [ ] **Step 4: Generate the wrapper**

```bash
gradle wrapper --gradle-version 8.10 || ./gradlew wrapper
```
> If `gradle` is absent, copy `gradle/wrapper/` + `gradlew` from the upstream ExampleMod, then run `./gradlew --version`.
Expected: `./gradlew --version` prints Gradle 8.x on JDK 17.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle build.gradle gradle.properties gradle/ gradlew gradlew.bat
git commit -m "chore: GTNH buildscript skeleton (RFG + convention plugin)"
```

### Task 1.3: Verify the toolchain resolves and assembles MC deps

**Files:** none (verification only)

- [ ] **Step 1: Resolve dependencies / set up the workspace**

```bash
./gradlew --refresh-dependencies build -x test
```
Expected: BUILD SUCCESSFUL; an (empty) mod jar appears in `build/libs/`. First run downloads MC, MCP mappings, Forge, and the convention deps (slow).

- [ ] **Step 2: Commit any generated lockfiles the convention adds (e.g. `dependencies.gradle`/lockfiles)**

```bash
git status   # add only convention-generated tracked files (not build/)
git add -A && git commit -m "chore: lock GTNH dependency resolution" || echo "nothing to lock"
```

### Task 1.4: `@Mod` entry point + `mcmod.info`

**Files:**
- Create: `src/main/kotlin/io/github/muindor/tcresearchsolver/TcResearchSolverMod.kt`
- Create: `src/main/resources/mcmod.info`

- [ ] **Step 1: Write the mod class**

```kotlin
package io.github.muindor.tcresearchsolver

import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import org.apache.logging.log4j.LogManager

@Mod(
    modid = TcResearchSolverMod.MODID,
    name = "Thaumcraft Research Solver",
    version = Tags.VERSION,
    dependencies = "required-after:Thaumcraft;required-after:thaumcraftresearchtweaks;required-after:gtnhmixins",
    acceptedMinecraftVersions = "[1.7.10]",
)
class TcResearchSolverMod {
    private val log = LogManager.getLogger(MODID)

    @Mod.EventHandler
    fun preInit(e: FMLPreInitializationEvent) {
        log.info("Thaumcraft Research Solver preInit")
    }

    @Mod.EventHandler
    fun init(e: FMLInitializationEvent) {
        log.info("Thaumcraft Research Solver init")
    }

    companion object {
        const val MODID = "tcresearchsolver"
    }
}
```
> `Tags` is generated by the convention (`generateGradleTokenClass`). If the convention is not configured to generate it, replace `Tags.VERSION` with a literal and remove the token keys from `gradle.properties`. Confirm the ResearchTweaks **modid string** during Phase 4 investigation (Task 4.1) and fix the `dependencies =` line; UniMixins registers as `gtnhmixins`/`spongemixins` depending on version — confirm and adjust.

- [ ] **Step 2: `mcmod.info`**

```json
[{
  "modid": "tcresearchsolver",
  "name": "Thaumcraft Research Solver",
  "description": "Adds a Solve button to the Thaumcraft research table that auto-completes the aspect minigame.",
  "version": "${version}",
  "mcversion": "${mcversion}",
  "authorList": ["muindor"],
  "dependencies": ["Thaumcraft", "thaumcraftresearchtweaks", "Forgelin"]
}]
```

- [ ] **Step 3: Build**

```bash
./gradlew build -x test
```
Expected: BUILD SUCCESSFUL; jar in `build/libs/`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/muindor/tcresearchsolver/TcResearchSolverMod.kt src/main/resources/mcmod.info
git commit -m "feat: @Mod entry point and mcmod.info"
```

### Task 1.5: Enable Kotlin + a JUnit 5 smoke test

**Files:**
- Modify: `build.gradle` (Kotlin plugin + JUnit deps, if the convention doesn't already)
- Create: `src/test/kotlin/io/github/muindor/tcresearchsolver/SmokeTest.kt`

- [ ] **Step 1: Ensure Kotlin + test deps**

If `./gradlew tasks` shows no Kotlin compile task, add to `build.gradle` after the plugins block:
```groovy
apply plugin: "org.jetbrains.kotlin.jvm"

dependencies {
    testImplementation platform("org.junit:junit-bom:5.10.2")
    testImplementation "org.junit.jupiter:junit-jupiter"
    testImplementation "com.google.code.gson:gson:2.10.1"
}
test { useJUnitPlatform() }
```
> The GTNH convention may already apply Kotlin and a test platform. Run `./gradlew dependencies --configuration testRuntimeClasspath` first; only add what is missing. Forgelin provides the Kotlin **runtime** in-game; the build still needs the Kotlin **compiler** plugin for `src/main/kotlin`.

- [ ] **Step 2: Smoke test**

```kotlin
package io.github.muindor.tcresearchsolver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test fun `kotlin junit runs`() = assertEquals(4, 2 + 2)
}
```

- [ ] **Step 3: Run**

```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, 1 test passed.

- [ ] **Step 4: Commit**

```bash
git add build.gradle src/test/kotlin/io/github/muindor/tcresearchsolver/SmokeTest.kt
git commit -m "chore: enable Kotlin source set + JUnit 5 smoke test"
```

### Task 1.6: Add Thaumcraft + ResearchTweaks + UniMixins as deobf deps

**Files:**
- Modify: `gradle.properties` or `dependencies.gradle` (per convention)

- [ ] **Step 1: Declare the deps**

In the convention's dependency file (`dependencies.gradle` if present, else `build.gradle`):
```groovy
dependencies {
    // Versions: confirm exact coordinates on the GTNH nexus; match what GTNH 2.8.4 bundles.
    implementation("com.github.GTNewHorizons:Thaumcraft:4.2.3.5a:dev")
    implementation("com.github.GTNewHorizons:ThaumcraftResearchTweaks:1.3.0:dev")
    implementation("com.github.GTNewHorizons:Forgelin:<pin>:dev")
    runtimeOnlyNonPublishable("com.github.GTNewHorizons:lwjgl3ify:<pin>")        // dev runClient on JDK 17
    runtimeOnlyNonPublishable("com.github.GTNewHorizons:Hodgepodge:<pin>")
}
```
> Resolve `<pin>` versions from `https://nexus.gtnewhorizons.com/repository/public/`. The deobf `:dev` classifier is what GTNH publishes for compile-time. If a coordinate 404s, search the nexus for the artifactId; coordinates drift between GTNH releases.

- [ ] **Step 2: Verify resolution + that TC classes are importable**

```bash
./gradlew dependencies --configuration runtimeClasspath | grep -i -E "thaumcraft|forgelin|mixin"
```
Expected: all three resolve with no "FAILED".

- [ ] **Step 3: Commit**

```bash
git add gradle.properties dependencies.gradle build.gradle 2>/dev/null; git commit -am "chore: deobf deps for Thaumcraft, ResearchTweaks, Forgelin, UniMixins"
```

### Task 1.7: Empty mixin config + plugin wiring

**Files:**
- Create: `src/main/resources/mixins.tcresearchsolver.json`

- [ ] **Step 1: Write the mixin config (no mixins yet)**

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.muindor.tcresearchsolver.mixin",
  "compatibilityLevel": "JAVA_8",
  "refmap": "mixins.tcresearchsolver.refmap.json",
  "client": [],
  "mixins": [],
  "injectors": { "defaultRequire": 1 }
}
```
> With `usesMixins = true` the convention registers this config + the UniMixins tweaker in the manifest automatically. Confirm the manifest contains `MixinConfigs: mixins.tcresearchsolver.json` after `./gradlew build` (unzip the jar's `META-INF/MANIFEST.MF`).

- [ ] **Step 2: Build + verify manifest**

```bash
./gradlew build -x test && unzip -p build/libs/*.jar META-INF/MANIFEST.MF | grep -i mixin
```
Expected: `MixinConfigs: mixins.tcresearchsolver.json` present.

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/mixins.tcresearchsolver.json
git commit -m "chore: empty mixin config registered via convention"
```

### Task 1.8: `runClient` smoke (manual)

**Files:** none

- [ ] **Step 1: Launch the dev client**

Suggest the user run it interactively (long-lived, GUI):
```
! ./gradlew runClient
```
Expected: the GTNH dev client boots; the FML mod list shows **Thaumcraft Research Solver**, **Thaumcraft**, and **ThaumcraftResearchTweaks**; our preInit/init log lines appear in the console.

- [ ] **Step 2: Confirm the research table GUI is ResearchTweaks'**

In a creative world, place a research table, right-click it, confirm the ResearchTweaks GUI (not vanilla) opens. Close the client.

- [ ] **Step 3: Commit a short dev note**

```bash
mkdir -p docs && printf '%s\n' "runClient verified: TC + ResearchTweaks load; RT GUI confirmed." >> docs/DEVLOG.md
git add docs/DEVLOG.md && git commit -m "docs: record runClient smoke verification"
```

---

# Phase 2 — Solver core port (TDD) + golden cross-check

**Exit criteria:** `./gradlew test` runs the full ported solver suite green, and `GoldenSolverTest` matches the TS oracle on `(status, scarcity, cells)` for every fixture scenario. The `solver` package imports **no** Minecraft types.

> Port order respects dependencies: Hex → Cost → AspectData/Raw → AspectGraph → Inventory → Steiner → Board → Heuristic → Solver. Each task: write the ported test(s), run red, port the impl, run green, commit.

### Task 2.1: `Hex` (port of `core/hex.ts`)

**Files:**
- Create: `src/main/kotlin/io/github/muindor/tcresearchsolver/solver/Hex.kt`
- Test: `src/test/kotlin/io/github/muindor/tcresearchsolver/solver/HexTest.kt`

- [ ] **Step 1: Port the test (`tests/core/hex.test.ts`)**

```kotlin
package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HexTest {
    @Test fun `round-trips a coord`() {
        assertEquals(Hex(-2, 3), parseHexKey(hexKey(Hex(-2, 3))))
        assertEquals("0,0", hexKey(Hex(0, 0)))
    }
    @Test fun `6 unit directions summing to zero`() {
        assertEquals(6, HEX_DIRECTIONS.size)
        assertEquals(Hex(0, 0), HEX_DIRECTIONS.fold(Hex(0, 0)) { a, d -> Hex(a.q + d.q, a.r + d.r) })
    }
    @Test fun `6 distinct neighbors at distance 1`() {
        val n = neighborsOf(Hex(0, 0))
        assertEquals(6, n.size)
        n.forEach { assertEquals(1, distance(Hex(0, 0), it)) }
        assertEquals(6, n.map { hexKey(it) }.toSet().size)
    }
    @Test fun `cube distance`() {
        assertEquals(0, distance(Hex(0, 0), Hex(0, 0)))
        assertEquals(2, distance(Hex(0, 0), Hex(2, -1)))
        assertEquals(4, distance(Hex(-1, -1), Hex(1, 1)))
    }
    @Test fun `cells per radius 1+3R(R+1)`() {
        assertEquals(19, boardCells(2).size)
        assertEquals(37, boardCells(3).size)
        assertEquals(61, boardCells(4).size)
        assertEquals(91, boardCells(5).size)
    }
    @Test fun `isOnBoard agrees with distance`() {
        assertTrue(isOnBoard(Hex(2, 0), 2))
        assertFalse(isOnBoard(Hex(3, 0), 2))
        assertTrue(boardCells(3).all { isOnBoard(it, 3) })
    }
    @Test fun `rejects malformed hex keys`() {
        for (bad in listOf("1", ",1", "1,", "1,2,3", "", "a,b", "1.5,2", " ,3"))
            assertThrows(IllegalArgumentException::class.java) { parseHexKey(bad) }
    }
}
```

- [ ] **Step 2: Run red** — `./gradlew test --tests '*HexTest'` → FAIL (unresolved `Hex`).

- [ ] **Step 3: Port the implementation** (apply G1, G6, G10)

```kotlin
package io.github.muindor.tcresearchsolver.solver

data class Hex(val q: Int, val r: Int)

val HEX_DIRECTIONS: List<Hex> = listOf(
    Hex(1, 0), Hex(1, -1), Hex(0, -1),
    Hex(-1, 0), Hex(-1, 1), Hex(0, 1),
)

fun hexKey(h: Hex): String = "${h.q},${h.r}"

private val HEX_KEY_RE = Regex("^-?\\d+$")

fun parseHexKey(key: String): Hex {
    val i = key.indexOf(',')
    if (i < 0 || i != key.lastIndexOf(',')) throw IllegalArgumentException("bad hex key: $key")
    val qs = key.substring(0, i)
    val rs = key.substring(i + 1)
    if (!HEX_KEY_RE.matches(qs) || !HEX_KEY_RE.matches(rs)) throw IllegalArgumentException("bad hex key: $key")
    return Hex(qs.toInt(), rs.toInt())
}

fun neighborsOf(h: Hex): List<Hex> = HEX_DIRECTIONS.map { Hex(h.q + it.q, h.r + it.r) }

fun distance(a: Hex, b: Hex): Int {
    val dq = a.q - b.q; val dr = a.r - b.r
    return (kotlin.math.abs(dq) + kotlin.math.abs(dr) + kotlin.math.abs(dq + dr)) / 2
}

fun isOnBoard(h: Hex, radius: Int): Boolean = distance(Hex(0, 0), h) <= radius

fun boardCells(radius: Int): List<Hex> {
    val cells = ArrayList<Hex>()
    for (q in -radius..radius) {
        val rLo = maxOf(-radius, -q - radius)
        val rHi = minOf(radius, -q + radius)
        for (r in rLo..rHi) cells.add(Hex(q, r))
    }
    return cells
}
```

- [ ] **Step 4: Run green** — `./gradlew test --tests '*HexTest'` → PASS.

- [ ] **Step 5: Commit** — `git add … && git commit -m "feat(solver): port Hex"`

### Task 2.2: `Cost` (port of `core/cost.ts`)

**Files:**
- Create: `src/main/kotlin/.../solver/Cost.kt`
- Test: `src/test/kotlin/.../solver/CostTest.kt`

- [ ] **Step 1: Port the test (`tests/core/cost.test.ts`)** — assert `compareCost` orders by scarcity then cells, `lessThan`, `addCost`, and that `INF_COST` compares greater than any finite. (Reproduce each `it()` from the TS file as a `@Test`.)

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Port impl** (apply G3, G4)

```kotlin
package io.github.muindor.tcresearchsolver.solver

data class Cost(val scarcity: Double, val cells: Double)

val ZERO_COST = Cost(0.0, 0.0)
val INF_COST = Cost(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)

fun compareCost(a: Cost, b: Cost): Int {
    if (a.scarcity != b.scarcity) return if (a.scarcity < b.scarcity) -1 else 1
    if (a.cells != b.cells) return if (a.cells < b.cells) -1 else 1
    return 0
}
fun lessThan(a: Cost, b: Cost): Boolean = compareCost(a, b) < 0
fun addCost(a: Cost, b: Cost): Cost = Cost(a.scarcity + b.scarcity, a.cells + b.cells)
```

- [ ] **Step 4: Run green. Step 5: Commit** `feat(solver): port Cost`.

### Task 2.3: `Raw` + `AspectData` (port of `data/raw.ts` + `data/aspects.ts`)

**Files:**
- Create: `src/main/kotlin/.../solver/Raw.kt`, `src/main/kotlin/.../solver/AspectData.kt`
- Test: `src/test/kotlin/.../solver/AspectDataTest.kt`

- [ ] **Step 1: Port `tests/data/aspects.test.ts`** to `AspectDataTest.kt`: build default data, assert primals present, every universe member has a translation, adjacency is symmetric (`a∈adj[b] ⇔ b∈adj[a]`), self-reference/undefined-component/cycle inputs throw `AspectDataError`, and `order` starts with the primals in `PRIMALS` order. (Reproduce each TS case.)

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Port `Raw.kt`** — transcribe the four tables from `data/raw.ts` (257 lines): `PRIMALS: List<String>`, `COMBINATIONS_4_2_2_0: LinkedHashMap<String, Pair<String,String>>`, `ADDONS: Map<String, Addon>` where `Addon(aspects: List<String>, combinations: LinkedHashMap<String, Pair<String,String>>)`, `TRANSLATE: Map<String,String>`. Preserve **declaration order** (G2): use `linkedMapOf(...)` so `COMBINATIONS_4_2_2_0` iterates exactly as the TS object does.

- [ ] **Step 4: Port `AspectData.kt`** (apply G1, G2, G10). Types + `buildAspectData`:

```kotlin
package io.github.muindor.tcresearchsolver.solver

typealias Aspect = String

class AspectData(
    val primals: Set<Aspect>,
    val combinations: Map<Aspect, Pair<Aspect, Aspect>>, // LinkedHashMap
    val universe: Set<Aspect>,                            // LinkedHashSet, insertion order
    val translate: Map<Aspect, String>,
    val adjacency: Map<Aspect, Set<Aspect>>,             // LinkedHashMap of LinkedHashSet
    val order: List<Aspect>,
)

class AspectDataError(message: String) : RuntimeException(message)

class BuildOptions(
    val addons: List<String>? = null,                     // default fm,mb,gt,tb,av
    val overrideCombinations: Map<String, Pair<Aspect, Aspect>>? = null,
    val overrideTranslate: Map<String, String>? = null,
    val overrideDeclaredAspects: List<String>? = null,
)
```
Then `fun buildAspectData(opts: BuildOptions = BuildOptions()): AspectData` translating `data/aspects.ts` exactly: build `combos` (override or `COMBINATIONS_4_2_2_0` + selected `ADDONS`), `declared`, then `universe = primals ∪ declared ∪ keys ∪ components` (insertion order: primals first, then per-combo `k, c1, c2`), self-reference + undefined-component checks, `detectCycle`, `translate` for every universe member (throw if missing), undirected `adjacency` (compound↔component, deduped), and `order = primals + combos.keys` then any remaining universe member. Use `LinkedHashSet`/`LinkedHashMap` throughout. `iconLatin` too; skip the browser-only `iconFile`.

- [ ] **Step 5: Run green. Step 6: Commit** `feat(solver): port AspectData + Raw tables`.

### Task 2.4: `AspectGraph` (port of `core/aspectGraph.ts`)

**Files:** Create `solver/AspectGraph.kt`; Test `solver/AspectGraphTest.kt`.

- [ ] **Step 1: Port `tests/core/aspectGraph.test.ts`** — `isValidLink` true for compound↔component & false for unrelated/self; `mult` returns 0/1/2 (incl. the doubled-component recipe case); `neighbors` returns a defensive copy; `primalVec` of a primal is `{a:1}` and of a compound is the recursive primal sum; unknown aspect throws.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Port impl** (apply G2, G7, G10). `neighbors`, `isValidLink` (hot path: read backing set directly, no copy), `mult`, and `primalVec` with an instance-scoped memo (`HashMap<AspectData, HashMap<Aspect, Map<Aspect,Int>>>` or a memo field on `AspectData`; G7) recursing through `combinations`, throwing if an aspect is neither primal nor compound.

- [ ] **Step 4: Run green. Step 5: Commit** `feat(solver): port AspectGraph`.

### Task 2.5: `Inventory` incl. the `allocate` DP (port of `core/inventory.ts`)

**Files:** Create `solver/Inventory.kt`; Test `solver/InventoryCostTest.kt`, `solver/InventoryAllocateTest.kt`.

This is the most error-prone port — the `allocate` exact DP. Full Kotlin inlined.

- [ ] **Step 1: Port `tests/core/inventory.cost.test.ts`** → `InventoryCostTest.kt`: `directPenalty` (0 at/above threshold; `BASE + K*(threshold - s)` when `0<s<threshold`; `+Inf` at 0 supply); `obtainCost` takes the min of direct vs craft-from-components and is `+Inf` only when unobtainable; `globalMinObtain` over the universe; `validateInventory` throws on negative/non-integer supply or `threshold<=0`.

- [ ] **Step 2: Port `tests/core/inventory.allocate.test.ts`** → `InventoryAllocateTest.kt`: feasible split prefers direct draw where cheaper; crafts when supply is short; `feasible=false` when a primal is demanded beyond supply with no recipe; `feasible='unknown'` (model as an enum) when `maxNodes` is tiny; `leafConsumption` records only direct draws; budget exhaustion returns `+Inf` scarcity and never a partial. (Reproduce each TS case.)

- [ ] **Step 3: Run red.**

- [ ] **Step 4: Port impl** (apply G2, G4, G7, G11). Note the `feasible: boolean | 'unknown'` union → a Kotlin enum.

```kotlin
package io.github.muindor.tcresearchsolver.solver

const val DEFAULT_THRESHOLD = 50
const val BASE = 1.0
const val K = 1.0

class Inventory(val supply: Map<Aspect, Int>, val threshold: Int = DEFAULT_THRESHOLD)

fun makeInventory(entries: List<Pair<Aspect, Int>>, threshold: Int = DEFAULT_THRESHOLD): Inventory =
    Inventory(LinkedHashMap<Aspect, Int>().apply { entries.forEach { put(it.first, it.second) } }, threshold)

fun validateInventory(inv: Inventory) {
    if (inv.threshold <= 0) throw IllegalArgumentException("threshold must be > 0, got ${inv.threshold}")
    for ((a, n) in inv.supply) if (n < 0) throw IllegalArgumentException("supply['$a'] must be a non-negative integer, got $n")
}

private fun supplyOf(inv: Inventory, a: Aspect): Int = inv.supply[a] ?: 0

fun directPenalty(inv: Inventory, a: Aspect): Double {
    val s = supplyOf(inv, a)
    if (s >= inv.threshold) return 0.0
    if (s > 0) return BASE + K * (inv.threshold - s)
    return Double.POSITIVE_INFINITY
}

// obtainCost: min(direct, craft-from-components); memo per (data,inv) — here a fresh map per call site
// is fine, but the solver reuses one map across a solve for speed (pass a cache in the hot path).
fun obtainCost(inv: Inventory, data: AspectData, a: Aspect, cache: HashMap<Aspect, Double> = HashMap()): Double {
    fun rec(x: Aspect, stack: HashSet<Aspect>): Double {
        cache[x]?.let { return it }
        if (x in stack) return Double.POSITIVE_INFINITY
        var best = directPenalty(inv, x)
        val recipe = data.combinations[x]
        if (recipe != null) {
            stack.add(x)
            val craft = rec(recipe.first, stack) + rec(recipe.second, stack)
            stack.remove(x)
            if (craft < best) best = craft
        }
        cache[x] = best
        return best
    }
    return rec(a, HashSet())
}

fun globalMinObtain(inv: Inventory, data: AspectData, cache: HashMap<Aspect, Double> = HashMap()): Double {
    var min = Double.POSITIVE_INFINITY
    for (a in data.universe) { val c = obtainCost(inv, data, a, cache); if (c < min) min = c }
    return min
}

enum class Feasible { TRUE, FALSE, UNKNOWN }
class AllocBudget(val maxNodes: Int = 200_000)
class AllocationResult(
    val feasible: Feasible,
    val scarcityCost: Double,
    val craftOps: Int,
    val leafConsumption: Map<Aspect, Int>,
)

private fun reverseTopoOrder(data: AspectData, aspects: Iterable<Aspect>): List<Aspect> {
    val order = ArrayList<Aspect>(); val seen = HashSet<Aspect>()
    fun visit(a: Aspect) {
        if (!seen.add(a)) return
        data.combinations[a]?.let { it.toList().forEach { c -> visit(c) } }
        order.add(a)
    }
    for (a in aspects) visit(a)
    order.reverse()
    return order
}

private class AllocSub(val cost: Double, val craftOps: Int, val direct: HashMap<Aspect, Int>)

fun allocate(inv: Inventory, data: AspectData, demand: Map<Aspect, Int>, budget: AllocBudget = AllocBudget()): AllocationResult {
    validateInventory(inv)
    val order = reverseTopoOrder(data, demand.keys)          // parents precede components
    val idx = HashMap<Aspect, Int>().apply { order.forEachIndexed { i, a -> put(a, i) } }
    val n = order.size
    val penalty = DoubleArray(n) { directPenalty(inv, order[it]) }
    val supplyArr = IntArray(n) { inv.supply[order[it]] ?: 0 }
    val need0 = IntArray(n) { demand[order[it]] ?: 0 }

    val memo = HashMap<String, Any>()                         // AllocSub or INFEASIBLE sentinel
    val INFEASIBLE = Any()
    var nodes = 0
    var budgetExhausted = false

    fun rec(i: Int, need: IntArray): Any {
        if (budgetExhausted) return INFEASIBLE
        if (i == n) return AllocSub(0.0, 0, HashMap())
        val key = buildString { append(i); append('|'); for (j in i until n) { if (j > i) append(','); append(need[j]) } }
        memo[key]?.let { return it }
        if (++nodes > budget.maxNodes) { budgetExhausted = true; return INFEASIBLE }

        val x = order[i]; val want = need[i]; val avail = supplyArr[i]; val pen = penalty[i]
        val recipe = data.combinations[x]
        val maxDirect = minOf(want, avail)
        var best: Any = INFEASIBLE

        var d = maxDirect
        while (d >= 0) {
            val c = want - d
            if (c > 0 && recipe == null) { d--; continue }
            if (d > 0 && !pen.isFinite()) { d--; continue }
            val need2 = need.copyOf()
            if (c > 0 && recipe != null) {
                for (comp in linkedSetOf(recipe.first, recipe.second)) {
                    val j = idx.getValue(comp)                // j > i by topo order
                    need2[j] = need2[j] + mult(data, comp, x) * c
                }
            }
            val sub = rec(i + 1, need2)
            if (budgetExhausted) return INFEASIBLE            // never memoize/return past exhaustion
            if (sub === INFEASIBLE) { d--; continue }
            sub as AllocSub
            val cost = (if (d > 0) d * pen else 0.0) + sub.cost
            val craftOps = c + sub.craftOps
            if (best === INFEASIBLE || cost < (best as AllocSub).cost ||
                (cost == best.cost && craftOps < best.craftOps)) {
                val direct = HashMap(sub.direct)
                if (d > 0) direct[x] = d
                best = AllocSub(cost, craftOps, direct)
            }
            d--
        }
        memo[key] = best
        return best
    }

    val result = rec(0, need0)
    if (budgetExhausted) return AllocationResult(Feasible.UNKNOWN, Double.POSITIVE_INFINITY, 0, emptyMap())
    if (result === INFEASIBLE) return AllocationResult(Feasible.FALSE, Double.POSITIVE_INFINITY, 0, emptyMap())
    result as AllocSub
    val leaf = LinkedHashMap<Aspect, Int>()
    for ((a, dd) in result.direct) if (dd > 0) leaf[a] = dd
    return AllocationResult(Feasible.TRUE, result.cost, result.craftOps, leaf)
}
```
> The memo key must enumerate `need[i..n)` exactly like the TS `need.slice(i).join(',')`. `linkedSetOf(recipe.first, recipe.second)` mirrors `new Set(recipe)` (dedupes a doubled component while `mult` still multiplies by 2).

- [ ] **Step 5: Run green. Step 6: Commit** `feat(solver): port Inventory + allocate DP`.

### Task 2.6: `Steiner` (port of `core/steiner.ts`)

**Files:** Create `solver/Steiner.kt`; Test `solver/SteinerTest.kt`.

- [ ] **Step 1: Port `tests/core/steiner.test.ts`** — single terminal returns its weight; a path graph returns the inner-node weight sum; disconnected terminals return `+Inf`; the `>MAX_STEINER_TERMINALS` guard throws; a small grid matches the known minimum.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Port impl** (apply G4, G9). `SteinerGraph` as an interface with `size: Int`, `neighbors(v): List<Int>`, `weight(v): Double`, `terminals: List<Int>`. Translate `steinerNodeWeighted` and `dijkstraLayer` verbatim: `dp = DoubleArray((1 shl k) * n) { Double.POSITIVE_INFINITY }`, submask enumeration `var s = (mask - 1) and mask; while (s > 0) { … s = (s - 1) and mask }` with the `if (s < other) break` symmetry cut, `merge` step `a + b - wv`, then `dijkstraLayer` (O(n²), `BooleanArray` visited). `MAX_STEINER_TERMINALS = 16`.

- [ ] **Step 4: Run green. Step 5: Commit** `feat(solver): port Steiner`.

### Task 2.7: `Board` (port of `core/board.ts`)

**Files:** Create `solver/Board.kt`; Test `solver/BoardModelTest.kt`, `solver/BoardValidateTest.kt`, `solver/BoardSerializeTest.kt`.

- [ ] **Step 1: Port the three test files** (`board.model.test.ts`, `board.validate.test.ts`, `board.serialize.test.ts`) — `createBoard` radius bounds (2..5) throw; `getState`/`setState` (EMPTY deletes the key); off-board access throws; `filledCells`/`anchorCells`/`filledNeighbors`; `validate` flags `SAME_ASPECT_ADJACENT`, `INVALID_LINK`, `ANCHORS_DISCONNECTED`; `serializeBoard`/`deserializeBoard` round-trip and reject bad schema/coord/aspect/state.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Port impl** (apply G1, G2, G6, G10). Model `CellState` as a sealed class:

```kotlin
sealed class CellState {
    object Dead : CellState()
    data class Anchor(val aspect: Aspect) : CellState()
    object Empty : CellState()
    data class Placed(val aspect: Aspect, val locked: Boolean) : CellState()
}
class Board(val radius: Int, val cells: LinkedHashMap<String, CellState>)
```
Translate `createBoard`, `getState` (absent ⇒ `Empty`), `setState` (`Empty` deletes), `filledCells`, `anchorCells`, `filledNeighbors`, `validate` (+ `anchorsConnectedInternal` BFS over filled adjacency, dedupe ordered pairs with `hexKey(c) >= nk` via G6 string compare), `isComplete`, and `serializeBoard`/`deserializeBoard` (keep the schema-version check; `deserializeBoard` validates aspects against `data.universe` and parses coords with `parseHexKey`). `ValidationError(type, cells)`, `ValidationResult(valid, errors)`, `ValidationErrorType` enum.

- [ ] **Step 4: Run green. Step 5: Commit** `feat(solver): port Board`.

### Task 2.8: `Heuristic` (port of `core/heuristic.ts`)

**Files:** Create `solver/Heuristic.kt`; Test `solver/HeuristicTest.kt`.

- [ ] **Step 1: Port `tests/core/heuristic.test.ts`** — `≤1` terminal ⇒ `{0,0}`; an admissible lower bound (never exceeds the true remainder on a hand-built board); `cells` is `+Inf` when terminals can't connect.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Port impl** (apply G2, G4). Translate `buildCellGraph` (assign ids to non-DEAD cells in `boardCells` order; build `adj`; BFS anchor-components; one representative terminal per anchor-bearing component), `steinerWith` (filled cells weight 0, free cells `freeWeight`), and `remainderHeuristic` (return `{0,0}` if `terminals.size ≤ 1`; else `scarcity = steinerWith(w)` with `w = globalMinObtain`, `cells = steinerWith(1.0)` coerced to `+Inf` when not finite).

- [ ] **Step 4: Run green. Step 5: Commit** `feat(solver): port Heuristic`.

### Task 2.9: `Solver` — `solve` + `solveWithValidation` (port of `core/solver.ts`)

**Files:** Create `solver/Solver.kt`; Test `solver/SolverTest.kt`, `solver/SolverPrevalidateTest.kt`, `solver/SolverSeedTest.kt`.

- [ ] **Step 1: Port `tests/core/solver.test.ts`** → `SolverTest.kt` (reproduce all `it()` from the TS shown verbatim earlier: returns a valid connected board; the 1-cell-bridge completeness case leaving `-1,0` EMPTY; avoids DEAD; 3-anchor; trivial 0/1 anchor; `UNKNOWN_TIMEOUT` under a `maxNodes:1` budget; prefers abundant aspects; `UNSAT_PROVEN`/`INVALID_INPUT` for adjacent invalid anchors; allocator-budget exhaustion ⇒ `UNKNOWN_TIMEOUT`; beam mode still finds a valid board). Use the injected clock `now = { 0L }` where a test must be timing-independent.

- [ ] **Step 2: Port `tests/core/solver.prevalidate.test.ts`** → `SolverPrevalidateTest.kt`: `solveWithValidation` returns `INVALID_INPUT` for bad inventory, `>MAX_ANCHORS` anchors, and unfixable link errors; lets `ANCHORS_DISCONNECTED` through to `solve`.

- [ ] **Step 3: Port `tests/core/solver.seed.test.ts`** → `SolverSeedTest.kt`: with `seed=true` the seeded incumbent is accepted only when valid+feasible, and the final result is never worse than with `seed=false`.

- [ ] **Step 4: Run red.**

- [ ] **Step 5: Port impl** (apply G1–G11). Public surface:

```kotlin
enum class SolverStatus { OPTIMAL, FEASIBLE_TIMEOUT, UNKNOWN_TIMEOUT, INFEASIBLE_INVENTORY, UNSAT_PROVEN, CANCELLED, INVALID_INPUT }
const val MAX_ANCHORS = 8
class SolveBudget(val maxNodes: Int, val maxTimeMs: Long, val beam: Int? = null)
class Progress(val nodes: Int, val best: Cost?, val timeMs: Long, val status: String)
val DEFAULT_BUDGETS: Map<Int, SolveBudget> = mapOf(
    2 to SolveBudget(500_000, 5_000),
    3 to SolveBudget(2_000_000, 10_000),
    4 to SolveBudget(4_000_000, 20_000, beam = 12),
    5 to SolveBudget(6_000_000, 30_000, beam = 8),
)
fun budgetForRadius(radius: Int): SolveBudget = DEFAULT_BUDGETS[radius] ?: DEFAULT_BUDGETS.getValue(5)

class SolveOptions(
    val data: AspectData, val board: Board, val inventory: Inventory, val budget: SolveBudget,
    val allocBudget: AllocBudget = AllocBudget(),
    val seed: Boolean = false,
    val onProgress: ((Progress) -> Unit)? = null,
    val shouldCancel: (() -> Boolean)? = null,
    val now: () -> Long = { System.currentTimeMillis() },
)
class SolveResult(
    val status: SolverStatus, val board: Board? = null, val cost: Cost? = null,
    val allocation: AllocationResult? = null, val nodes: Int = 0, val timeMs: Long = 0,
    val errors: List<ValidationError>? = null, val message: String? = null,
)
```
Translate `solveWithValidation` (validate inventory → `INVALID_INPUT`; anchor cap → `INVALID_INPUT`; `validate` filtering out `ANCHORS_DISCONNECTED` → `INVALID_INPUT` on remaining; else `solve`) and `solve` **verbatim**, preserving every invariant comment:
  - `anchors.size ≤ 1` ⇒ trivial `OPTIMAL`.
  - initial unfixable invalidity ⇒ `UNSAT_PROVEN`.
  - working board clone + `placements` list + `excluded` set; `fastAnchorsConnected` BFS over `work.cells`; `placedCost` (sum `obtainCost`, cells = `placements.size.toDouble()`); `nextUndecidedFrontierCell` (lowest-hexKey EMPTY frontier cell via G6); `reportProgress`; `validPlacement`; `onComplete` (feasible ⇒ maybe update incumbent; `unknown` ⇒ set `anyUnknownCompetitive` but keep searching; `false` ⇒ discard); `dfs` (cancel/budget/time checks every 1024 nodes via `(nodes and 1023) == 0`; prune only with an incumbent using `addCost(g,h)` vs incumbent; INCLUDE branch with infinity-safe candidate sort (G5) + optional beam setting `truncated`; EXCLUDE branch); optional `seedIncumbent` (gated on `opts.seed`) with `cheapestProductPath` Dijkstra and the trusted accept gate; final status resolution (`CANCELLED` / `OPTIMAL` if exhaustive & no competitive-unknown / `FEASIBLE_TIMEOUT` / `UNSAT_PROVEN` / `INFEASIBLE_INVENTORY` / `UNKNOWN_TIMEOUT`). Reuse one `obtainCost` cache map across the solve for speed (G7).
  - Helpers `cloneBoard`, `demandOf`, `solverPlacements`, `validAgainstBoard` as in the TS.

- [ ] **Step 6: Run green** — `./gradlew test --tests '*solver*' --tests '*Solver*'`.

- [ ] **Step 7: Commit** `feat(solver): port branch-and-bound Solver`.

### Task 2.10: Solver micro-benchmark (re-tune budgets)

**Files:** Create `src/test/kotlin/.../solver/SolverBenchTest.kt` (tagged, opt-in).

- [ ] **Step 1:** Add a `@Test` tagged `@Tag("bench")` (excluded from the default `test` run via `useJUnitPlatform { excludeTags("bench") }`) that solves representative R2–R5 boards with rich inventory using `DEFAULT_BUDGETS`, printing `radius, status, nodes, timeMs`.

- [ ] **Step 2: Run** `./gradlew test --tests '*SolverBenchTest*' -PincludeTags=bench` (wire a property to flip the tag filter) and record results in `docs/DEVLOG.md`.

- [ ] **Step 3:** If R4/R5 exceed the ~20–30 s budgets on the JVM, tune `DEFAULT_BUDGETS` (`maxNodes`/`maxTimeMs`/`beam`) and re-run. Commit the tuned values: `perf(solver): tune per-radius budgets on JVM`.

### Task 2.11: TS oracle harness (golden fixture generator)

**Files:** Create `tools/oracle/generate-golden.ts`, `tools/oracle/package.json`.

The reference TS tests import from a non-existent `app/src`; we do **not** run them. Instead this harness imports the actual `reference/ts-solver/core|data` files (their relative imports are self-consistent) and emits deterministic fixtures. Determinism: inject `now: () => 0` so the time budget never trips, and use only **exhaustively-solvable** small instances so `(status, scarcity, cells)` is canonical and machine-independent.

- [ ] **Step 1: `package.json`**

```json
{ "name": "tcrs-oracle", "private": true, "type": "module" }
```

- [ ] **Step 2: `generate-golden.ts`**

```ts
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
];

const results: Record<string, { status: string; scarcity: number | "inf"; cells: number | null }> = {};
for (const sc of scenarios) {
  const b = createBoard(sc.radius);
  for (const c of sc.cells) {
    const [q, r] = c.key.split(",").map(Number);
    if (c.state === "ANCHOR") setState(b, { q, r }, { kind: "ANCHOR", aspect: c.aspect! });
    else if (c.state === "DEAD") setState(b, { q, r }, { kind: "DEAD" });
    else setState(b, { q, r }, { kind: "PLACED", aspect: c.aspect!, locked: c.locked ?? false });
  }
  const inv = sc.inv === "rich" ? rich() : makeInventory(Object.entries(sc.inv), sc.threshold ?? DEFAULT_THRESHOLD);
  const res = solveWithValidation({ data, board: b, inventory: inv, budget: { maxNodes: sc.maxNodes, maxTimeMs: HUGE_TIME }, now: fixedClock });
  results[sc.name] = {
    status: res.status,
    scarcity: res.cost ? (Number.isFinite(res.cost.scarcity) ? res.cost.scarcity : "inf") : "inf",
    cells: res.cost ? res.cost.cells : null,
  };
}
writeFileSync(OUT + "scenarios.json", JSON.stringify({ scenarios, results }, null, 2));
console.log("wrote", Object.keys(results).length, "scenarios");
```

- [ ] **Step 3: Generate**

```bash
cd tools/oracle && bun run generate-golden.ts
```
Expected: `wrote 5 scenarios`; `src/test/resources/golden/aspect-data.json` and `scenarios.json` created.

- [ ] **Step 4: Commit** — `git add tools/oracle src/test/resources/golden && git commit -m "test(solver): TS oracle harness + golden fixtures"`.

### Task 2.12: Golden cross-check test (Kotlin reads fixtures)

**Files:** Create `src/test/kotlin/.../solver/GoldenSolverTest.kt`.

- [ ] **Step 1: Write the test** (uses Gson):

```kotlin
package io.github.muindor.tcresearchsolver.solver

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

class GoldenSolverTest {
    private fun res(p: String) = File("src/test/resources/golden/$p").readText()

    @TestFactory
    fun `matches TS oracle on every scenario`(): List<DynamicTest> {
        val data = buildAspectData() // validated equal to oracle's by AspectDataTest golden assertion below
        val root = JsonParser.parseString(res("scenarios.json")).asJsonObject
        val scenarios = root.getAsJsonArray("scenarios")
        val results = root.getAsJsonObject("results")
        return scenarios.map { sEl ->
            val s = sEl.asJsonObject
            val name = s.get("name").asString
            DynamicTest.dynamicTest(name) {
                val b = createBoard(s.get("radius").asInt)
                for (cEl in s.getAsJsonArray("cells")) {
                    val c = cEl.asJsonObject
                    val (q, r) = c.get("key").asString.split(",").map { it.toInt() }
                    when (c.get("state").asString) {
                        "ANCHOR" -> setState(b, Hex(q, r), CellState.Anchor(c.get("aspect").asString))
                        "DEAD" -> setState(b, Hex(q, r), CellState.Dead)
                        else -> setState(b, Hex(q, r), CellState.Placed(c.get("aspect").asString, c.get("locked")?.asBoolean ?: false))
                    }
                }
                val invEl = s.get("inv")
                val inv = if (invEl.isJsonPrimitive)
                    makeInventory(data.universe.map { it to 100 })
                else makeInventory(invEl.asJsonObject.entrySet().map { it.key to it.value.asInt })
                val maxNodes = s.get("maxNodes").asInt
                val r = solveWithValidation(SolveOptions(
                    data = data, board = b, inventory = inv,
                    budget = SolveBudget(maxNodes, 3_600_000), now = { 0L }))
                val exp = results.getAsJsonObject(name)
                assertEquals(exp.get("status").asString, r.status.name, "$name status")
                val expScar = exp.get("scarcity").let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) Double.POSITIVE_INFINITY else it.asDouble }
                assertEquals(expScar, r.cost?.scarcity ?: Double.POSITIVE_INFINITY, 0.0, "$name scarcity")
                if (!exp.get("cells").isJsonNull)
                    assertEquals(exp.get("cells").asDouble, r.cost?.cells, "$name cells")
            }
        }
    }
}
```

- [ ] **Step 2: Add a golden assertion to `AspectDataTest`** that the Kotlin-built data equals the oracle dump (so any future divergence in the data port is caught): load `aspect-data.json`, assert `data.order == json.order`, `data.universe.toList() == json.universe`, and combinations match key-by-key.

- [ ] **Step 3: Run** `./gradlew test --tests '*GoldenSolverTest*' --tests '*AspectDataTest*'` → PASS.

- [ ] **Step 4: Commit** `test(solver): golden cross-check vs TS oracle`.

### Task 2.13: Full suite gate

- [ ] **Step 1: Run the whole suite** — `./gradlew test`. Expected: all green.
- [ ] **Step 2: Commit** any fixups: `test(solver): full solver suite green`.

---

# Phase 3 — Integration adapters (read live TC state; apply via packets)

**Exit criteria:** adapters compile against the deobf TC + ResearchTweaks classpath; `AspectDataProvider` builds an `AspectData` from the live registry; `BoardReader`/`InventoryReader` convert live note/pool to solver inputs; `Applier` can combine + place via packets (verified in Phase 5 runClient).

> These touch TC + RT internals. Each starts by **pinning the signature** from the decompiled jars (`reference/jars/`), because field/method names and the `HexEntry.type` ints are not yet confirmed.

### Task 3.1: Investigation — pin TC + RT signatures

**Files:** append findings to `reference/thaumcraft-integration.md` (a new "confirmed signatures" section).

- [ ] **Step 1: Dump the key TC classes**

```bash
cd reference/jars
javap -p -classpath Thaumcraft-1.7.10-4.2.3.5a.jar thaumcraft.api.aspects.Aspect thaumcraft.api.aspects.AspectList
javap -p -classpath Thaumcraft-1.7.10-4.2.3.5a.jar thaumcraft.common.lib.research.ResearchManager 'thaumcraft.common.lib.research.ResearchManager$HexEntry'
javap -p -classpath Thaumcraft-1.7.10-4.2.3.5a.jar thaumcraft.api.research.ResearchNoteData thaumcraft.lib.utils.HexUtils 2>/dev/null || javap -p -classpath Thaumcraft-1.7.10-4.2.3.5a.jar thaumcraft.common.lib.utils.HexUtils
javap -p -classpath Thaumcraft-1.7.10-4.2.3.5a.jar thaumcraft.common.lib.network.PacketHandler thaumcraft.common.lib.network.playerdata.PacketAspectPlaceToServer thaumcraft.common.lib.network.playerdata.PacketAspectCombinationToServer
javap -p -classpath Thaumcraft-1.7.10-4.2.3.5a.jar thaumcraft.common.tiles.TileResearchTable
```
Record exact package paths (the network packet package may differ — search with `unzip -l Thaumcraft-1.7.10-4.2.3.5a.jar | grep -i packetaspect`). Confirm the **`HexEntry.type` int constants** for ROOT/NODE/VACANT (decompile `ResearchManager` or `HexEntry`, or read a real note in dev in Task 3.2).

- [ ] **Step 2: Dump the RT adapter signatures**

```bash
javap -p -classpath ThaumcraftResearchTweaks-1.3.0.jar elan.tweaks.thaumcraft.adapters.ResearchNotesAdapter elan.tweaks.thaumcraft.adapters.AspectPoolAdapter elan.tweaks.thaumcraft.adapters.AspectCombinerAdapter elan.tweaks.thaumcraft.adapters.ScribeToolsAdapter 2>/dev/null
unzip -l ThaumcraftResearchTweaks-1.3.0.jar | grep -iE "adapter|GuiFactory|ComposableContainerGui|HexMapLayout|CopyButton" 
javap -p -classpath ThaumcraftResearchTweaks-1.3.0.jar <each fully-qualified class found above>
```
Record exact FQNs + method signatures for: `ResearchTableGuiFactory.create(...)`, `ComposableContainerGui`, the `UIComponent` interfaces, `CopyButtonUIComponent` (ctor + how it's added in the factory), `ParchmentHexMapLayout` (hex→pixel), and the adapters' ctor params + return types.

- [ ] **Step 3: Write the confirmed-signatures section** into `reference/thaumcraft-integration.md` and **commit**: `docs: confirmed TC/RT signatures from javap`.

### Task 3.2: `AspectDataProvider` — build `AspectData` live from the registry

**Files:** Create `integration/AspectDataProvider.kt`, `integration/TcTypes.kt`; Test `src/test/kotlin/.../integration/AspectDataProviderTest.kt`.

Because this needs the live `Aspect.aspects` registry (only present in-game), unit-test the **pure builder** against a small fake registry and reserve full-registry verification for runClient.

- [ ] **Step 1: Define a pure builder + test it**

Extract a pure function `buildAspectDataFrom(entries: List<RegistryEntry>): AspectData` where `RegistryEntry(tag: String, components: List<String>, isPrimal: Boolean)`. Test: a fake registry of `air`(primal), `entropy`(primal), `fire`(primal), `void=air+entropy`, `flux=entropy+fire` produces the right `primals`, `combinations`, symmetric `adjacency`, and `universe` order (primals first by registry order, then compounds). Reuse the same invariants as `AspectDataTest`.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Implement** `buildAspectDataFrom` (mirrors `buildAspectData` but from registry entries: `combinations[tag] = (c0, c1)` from `getComponents()`, adjacency `a~b iff b∈comp(a) || a∈comp(b)`, `primals` from `isPrimal`/`getPrimalAspects`). Then `AspectDataProvider.fromLiveRegistry()` reads `thaumcraft.api.aspects.Aspect.aspects` (a `LinkedHashMap<String,Aspect>`), maps each to a `RegistryEntry` via `getTag()`/`getComponents()`/`isPrimal()`, and calls the pure builder. Keep TC reflection/typed access in `TcTypes.kt`.

- [ ] **Step 4: Run green. Step 5: Commit** `feat(integration): live AspectData from TC registry`.

### Task 3.3: `BoardReader` — `ResearchNoteData` → solver `Board`

**Files:** Create `integration/BoardReader.kt`; Test `.../integration/BoardReaderTest.kt`.

- [ ] **Step 1: Define a pure mapper + test**

`fun toBoard(noteHexes: Map<String, Pair<Int,Int>>, entries: Map<String, NoteEntry>, radius: Int): Board` where `NoteEntry(aspectTag: String?, type: HexType)` and `HexType { VACANT, ROOT, NODE }`. ROOT → `Anchor`, NODE → `Placed(locked=true)` (already-written cells are fixed), VACANT/absent → `Empty`; cells not in `noteHexes` but on-board stay `Empty`; cells off the note's `hexes` shape but on the radius board → leave `Empty` (the grid shape is the union the solver may fill; if RT marks unusable cells, map them to `Dead` — decide from the confirmed `hexes`/`hexEntries` semantics in Task 3.1). Test with a synthetic R2 note: two ROOTs + one NODE → a Board with two `Anchor`s and one locked `Placed`.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Implement** the pure mapper, plus `BoardReader.read(note)` that calls `ResearchManager.getData(note)` and adapts `ResearchNoteData.hexes`/`hexEntries` (via the confirmed `HexEntry.type` ints) into the mapper inputs. Derive `radius` from the note's hex extent (max `|q|,|r|,|q+r|`).

- [ ] **Step 4: Run green. Step 5: Commit** `feat(integration): BoardReader note->Board`.

### Task 3.4: `InventoryReader` — `AspectPool` → solver `Inventory`

**Files:** Create `integration/InventoryReader.kt`; Test `.../integration/InventoryReaderTest.kt`.

- [ ] **Step 1: Define a pure mapper + test**

`fun toInventory(amounts: Map<String,Int>, threshold: Int): Inventory`. The pool amount per aspect = `amountOf + bonusAmountOf` (confirm whether `totalAmountOf` already sums these in Task 3.1; prefer it if so). Undiscovered aspects ⇒ absent ⇒ supply 0. Test: a fake amounts map → `Inventory.supply` matches; `threshold` passthrough.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Implement** the mapper + `InventoryReader.read(pool, data, threshold)` iterating `data.universe`, reading `totalAmountOf`/`amountOf+bonusAmountOf` per the confirmed `AspectPool`/`AspectPoolAdapter` API, gated by `hasDiscovered`.

- [ ] **Step 4: Run green. Step 5: Commit** `feat(integration): InventoryReader pool->Inventory`.

### Task 3.5: `Applier` — combine missing compounds + place via packets

**Files:** Create `integration/Applier.kt`; Test `.../integration/ApplierTest.kt`.

The Applier has two responsibilities split for testability: (a) **plan** the ordered list of combine-ops and place-ops from a `SolveResult` + the live pool (pure, unit-testable); (b) **execute** the plan by sending TC packets on the client thread (thin, verified at runClient).

- [ ] **Step 1: Define the plan model + pure planner + test**

```kotlin
sealed class ApplyOp {
    data class Combine(val a: Aspect, val b: Aspect) : ApplyOp()   // -> PacketAspectCombinationToServer
    data class Place(val key: String, val aspect: Aspect) : ApplyOp() // -> PacketAspectPlaceToServer
}
```
`fun planApply(result: SolveResult, data: AspectData, pool: Map<Aspect,Int>): List<ApplyOp>`: from `result.board` take solver-placed cells (PLACED & not in the initial note); for each placed aspect that the pool can't directly satisfy, emit the recursive `Combine` ops (deepest components first) needed to craft it, **before** the `Place` ops that consume them; placements ordered by hexKey (G6) for deterministic application. Test: a board needing `void` with zero `void` supply but abundant `air`+`entropy` ⇒ plan has `Combine(air, entropy)` before `Place(_, void)`.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Implement** `planApply` (use `allocate`'s `leafConsumption`/`craftOps` or recompute craft trees via `combinations`), then `Applier.apply(plan, player, tile)` that, on the client thread, gates on `ScribeTools.areMissingOrEmpty()` (abort with a reason if ink missing), then for each op sends:

```kotlin
// Place: new PacketAspectPlaceToServer(player, q.toByte(), r.toByte(), x, y, z, aspect)
// Combine: new PacketAspectCombinationToServer(player, x, y, z, a1, a2, false, false, false)
// PacketHandler.INSTANCE.sendToServer(packet)
```
(Resolve `(x,y,z)` from the `TileResearchTable`; map solver tag → TC `Aspect` via `Aspect.getAspect(tag)`. Confirm the `boolean ab1,ab2,ab3` combine flags meaning in Task 3.1 — default `false` unless the decompile shows otherwise.) Add `postVerify(note, plan)` that re-reads `ResearchManager.getData(note)` and returns the set of cells not present/rejected.

- [ ] **Step 4: Run green (planner test). Step 5: Commit** `feat(integration): Applier plan + packet execution`.

---

# Phase 4 — UI: Mixin injection, state machine, ghost overlay, apply

**Exit criteria:** the Solve button appears in the ResearchTweaks table GUI; clicking runs the solver off-thread with a progress spinner + cancel; a ghost overlay previews the solution; Apply fills the grid and the research completes (verified at runClient in Phase 5).

> UI is integration-heavy and best verified manually; keep unit tests on the pure state machine.

### Task 4.1: Investigation — pin the GUI injection seam

**Files:** append to `reference/researchtweaks-map.md` (confirmed-signatures section).

- [ ] **Step 1:** From the Task 3.1 dumps, confirm: the exact `ResearchTableGuiFactory` FQN + the `create(...)` signature and return type (`ComposableContainerGui`), how `CopyButtonUIComponent` is constructed and appended (the component list field/method), the `UIComponent`/`ClickableUIComponent`/`ForegroundUIComponent`/`TickingUIComponent` method signatures (render/click/tick + `UIContext`), and `ParchmentHexMapLayout`'s hex→pixel method. Confirm the **ResearchTweaks modid string** and the **UniMixins coremod name** for the `@Mod dependencies` line.
- [ ] **Step 2:** Decide the Mixin target: prefer `@Inject(at=@At("RETURN"))` into `ResearchTableGuiFactory.create(...)` to append components to the returned/under-construction `ComposableContainerGui`, capturing the layout + `TileResearchTable` + `Container` + `EntityPlayer` from the method args/locals. Record the chosen target + how to reach each captured ref.
- [ ] **Step 3: Commit** `docs: confirmed RT GUI injection seam`.

### Task 4.2: `SolveController` — pure client-thread state machine

**Files:** Create `ui/SolveController.kt`; Test `.../ui/SolveControllerTest.kt`.

- [ ] **Step 1: Write the test** for the state machine in isolation (no MC): states `Idle, Solving, Preview, Applying, Done, Error`; transitions: `start()` (Idle→Solving) only when a valid incomplete note snapshot is present; `onSolved(result)` (Solving→Preview on FEASIBLE/OPTIMAL, →Error/Idle on INFEASIBLE/UNSAT/UNKNOWN with a message); `cancel()` (Solving→Idle); `apply()` (Preview→Applying); `onApplied(verify)` (Applying→Done or Error with rejected cells); `reset()` (any→Idle). Drive it with injected fakes for the worker + applier so it's pure.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Implement** `SolveController` holding the current `State` (sealed class carrying payloads: `Solving(progress)`, `Preview(result)`, `Error(message)`), a snapshot of solver inputs, references to `SolveWorker` and `Applier` (via interfaces so tests fake them), and a `previewConfirm` flag (from config; if false, `onSolved` auto-applies). Exposes `render`-thread-friendly getters (current label, progress text, button mode).

- [ ] **Step 4: Run green. Step 5: Commit** `feat(ui): SolveController state machine`.

### Task 4.3: `SolveWorker` — background solve with progress/cancel

**Files:** Create `ui/SolveWorker.kt`; Test `.../ui/SolveWorkerTest.kt`.

- [ ] **Step 1: Write the test**: given a snapshot (data+board+inventory+budget), `SolveWorker.start(snapshot, onProgress, onDone)` runs `solveWithValidation` on a single background thread, forwards `Progress` via the `onProgress` callback (atomics), invokes `onDone(result)` once, and `cancel()` causes `shouldCancel()` to return true so the solve returns `CANCELLED`. Verify with a deliberately large board + `cancel()` shortly after start that `onDone` reports `CANCELLED`.

- [ ] **Step 2: Run red.**

- [ ] **Step 3: Implement** `SolveWorker` with a single `Thread`, an `AtomicBoolean cancelFlag` wired to `SolveOptions.shouldCancel`, `AtomicReference<Progress>` for the latest progress (polled each frame by the UI), and `now = { System.nanoTime()/1_000_000 }`. All Minecraft reads happen in the snapshot built on the client thread before `start`; the worker touches no MC state (spec §"Concurrency model").

- [ ] **Step 4: Run green. Step 5: Commit** `feat(ui): off-thread SolveWorker with progress + cancel`.

### Task 4.4: `SolveButtonUIComponent` (mirror `CopyButtonUIComponent`)

**Files:** Create `ui/SolveButtonUIComponent.kt`.

- [ ] **Step 1: Implement** a `ClickableUIComponent`/`MouseOverUIComponent` mirroring the confirmed `CopyButtonUIComponent` shape: position from the layout, render the button (label switches Solve/Cancel/Apply/Reset by `SolveController` state), `onClick` dispatches to the controller (`start`/`cancel`/`apply`/`reset`). Disabled+greyed when the controller reports the note is invalid/complete.
- [ ] **Step 2: Build** `./gradlew build -x test` (compiles against RT). Expected: SUCCESS.
- [ ] **Step 3: Commit** `feat(ui): Solve button component`.

### Task 4.5: `SpinnerComponent` (progress display)

**Files:** Create `ui/SpinnerComponent.kt`.

- [ ] **Step 1: Implement** a `TickingUIComponent`/`ForegroundUIComponent` that, while the controller is `Solving`, renders the spinner + `⏱ {s}s · nodes {n} · best {cells}` from the polled `Progress`, and is invisible otherwise.
- [ ] **Step 2: Build. Step 3: Commit** `feat(ui): progress spinner`.

### Task 4.6: `GhostOverlayComponent` (preview)

**Files:** Create `ui/GhostOverlayComponent.kt`.

- [ ] **Step 1: Implement** a `ForegroundUIComponent` that, while the controller is `Preview`, draws each solution placement as a translucent aspect icon at the hex's pixel position (via the confirmed `ParchmentHexMapLayout` hex→pixel), reusing RT's `AspectRenderer`/`RuneTexture` for styling. Also render the result metadata (e.g. "optimal" vs "best found — not proven optimal").
- [ ] **Step 2: Build. Step 3: Commit** `feat(ui): ghost preview overlay`.

### Task 4.7: The Mixin — inject our components into the factory

**Files:** Create `mixin/ResearchTableGuiFactoryMixin.kt`; Modify `src/main/resources/mixins.tcresearchsolver.json` (add the mixin to `client`).

- [ ] **Step 1: Implement** the Mixin per the Task 4.1 decision: `@Mixin(ResearchTableGuiFactory::class)`, `@Inject(method="create…", at=@At("RETURN"))` (or ctor), capturing the layout + tile + container + player, constructing a shared `SolveController` + the three components, and appending them to the `ComposableContainerGui`'s component list. Keep the injection surface minimal (append + capture).

- [ ] **Step 2: Register** the mixin in `mixins.tcresearchsolver.json` `"client": ["ResearchTableGuiFactoryMixin"]`.

- [ ] **Step 3: Build + verify the refmap** — `./gradlew build` and confirm `mixins.tcresearchsolver.refmap.json` maps the targeted method. Expected: SUCCESS, no mixin apply errors at `validateMixins`.

- [ ] **Step 4: Commit** `feat(ui): mixin injects Solve UI into ResearchTweaks GUI`.

---

# Phase 5 — Config, end-to-end verification, finish

### Task 5.1: Forge config

**Files:** Create `config/Config.kt`; wire into `TcResearchSolverMod.preInit`.

- [ ] **Step 1:** Implement a Forge `Configuration` reading: `maxSolveMs` (default per-radius budgets cap), `previewConfirm` (default true), and a keybind toggle. Expose to `SolveController`/`SolveWorker`.
- [ ] **Step 2:** Optional keybind that duplicates the button action when a research table GUI is open.
- [ ] **Step 3: Build + test. Step 4: Commit** `feat(config): Forge config (maxSolveMs, previewConfirm, keybind)`.

### Task 5.2: End-to-end runClient verification (manual)

**Files:** append results to `docs/DEVLOG.md`.

- [ ] **Step 1:** `! ./gradlew runClient`. In a creative world with Thaumcraft progressed enough to have a research note + aspects + ink: open the table, press **Solve**.
- [ ] **Step 2:** Confirm: spinner animates + cancel works; ghost preview appears aligned to the hexes at multiple window sizes; **Apply** combines missing compounds and fills the grid; the research completes; post-verify reports no rejected cells.
- [ ] **Step 3:** Test shortage paths: remove ink → Apply reports "out of ink"; remove a required aspect/zero a needed primal → Solve reports `INFEASIBLE_INVENTORY` with what's missing and stays Idle.
- [ ] **Step 4:** Record outcomes (incl. any rejected-cell reasons) in `docs/DEVLOG.md` and **commit** `docs: end-to-end runClient verification`.

### Task 5.3: Finish the branch

- [ ] **Step 1:** Run `./gradlew build test`. Expected: all green; jar built.
- [ ] **Step 2:** Use the `superpowers:finishing-a-development-branch` skill to decide merge/PR/cleanup.

---

## Self-Review

**1. Spec coverage** (design §-by-§):
- Goal / button / two-step UX → Phases 4–5 (4.2 controller, 4.4 button, 4.6 ghost, 5.2 E2E). ✓
- No TC source modified; Mixin into RT `ComposableContainerGui` → 4.1, 4.7. ✓
- Packaging (Kotlin/Forgelin/UniMixins/`ForceLoadAsMod`) → Phase 1 (1.2, 1.5, 1.6, 1.7). ✓
- Solver: verbatim TS port, lexicographic `scarcity→cells`, live aspect pool, compounds via combining → Phase 2 (2.1–2.13) + 3.2/3.4/3.5. ✓
- Architecture 3 layers (solver/integration/ui) → Phases 2/3/4. ✓
- State machine Idle→Solving→Preview→Applying→Done+Error → 4.2. ✓
- Concurrency (off-thread, client-thread snapshot + sends, atomics, cancel) → 4.3 + 3.5. ✓
- Apply correctness (combine before dependent place, server authoritative, post-verify) → 3.5, 5.2. ✓
- Build (RFG + GTNH, `mixins.<modid>.json` JAVA_8 refmap, mcmod.info deps) → 1.2/1.4/1.7. ✓
- Forge config (maxSolveMs, keybind, previewConfirm) → 5.1. ✓
- Testing (JUnit port + golden vs TS oracle; adapter tests; manual UI) → 2.x, 3.2–3.5, 5.2. ✓
- Risks: Mixin coupling (4.1 investigation + minimal surface), apply ordering (3.5 sequential + post-verify), JVM budgets (2.10 bench), aspect-graph completeness (3.2 live build), `HexEntry.type` ints (3.1/3.3 confirm). ✓
- Out of scope (vanilla GUI, server component, scanning automation) — not planned. ✓

**2. Placeholder scan:** Remaining `<pin>`/`<class>` tokens are in dependency coordinates and javap targets that *must* be resolved against the live GTNH nexus / decompiled jars at implementation time — each is paired with the exact command to resolve it. The `HexEntry.type` ints and several RT signatures are explicitly deferred to the named investigation tasks (3.1, 4.1) rather than guessed. No vague "add error handling"-style steps.

**3. Type consistency:** `Aspect=String`, `Cost(Double,Double)`, `CellState` sealed class, `Feasible` enum, `AllocationResult`, `SolveOptions/SolveResult/SolverStatus`, `ApplyOp` are defined once and reused consistently across Phases 2–4. `solveWithValidation`/`solve` signatures match between Task 2.9 and their callers in 2.12, 4.2, 4.3.

---

## Execution Handoff

(see below)
