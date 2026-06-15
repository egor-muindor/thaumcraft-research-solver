# ThaumcraftResearchTweaks map (the addon we hook into)

Upstream source: https://github.com/GTNewHorizons/thaumcraft-research-tweaks
Shipped in GTNH 2.8.4 as `ThaumcraftResearchTweaks-1.3.0.jar` (`reference/jars/`).
Kotlin (Forgelin), hexagonal architecture. It **replaces** the vanilla research table GUI and
implements manual note editing via TC packets — it does everything except auto-solve. We add the
solver + a "Solve" button. This file maps the seams we depend on. Read the upstream source during
implementation to pin exact signatures before writing the Mixin.

## Packaging / load pattern (mirror this)

`META-INF/MANIFEST.MF`:
```
FMLCorePluginContainsFMLMod: true
TweakClass: org.spongepowered.asm.launch.MixinTweaker
MixinConfigs: mixins.ThaumcraftResearchTweaks.json
ForceLoadAsMod: true
```
`mcmod.info` dependencies: `Forgelin`, `Thaumcraft`, `SpongeMixins` (UniMixins in GTNH).
Mixin config: `compatibilityLevel: JAVA_8`, `refmap` present, `injectors.defaultRequire: 1`.

## How it takes over the GUI

Single mixin `elan.tweaks.…table.mixin.TableBlockMixin` with private method
`correctGuiCallFor(EntityPlayer, Object, int guiId, World, int x, int y, int z)` — it redirects the
research table block's GUI open call to its own `ThaumcraftResearchGuiHandler`, which builds a
`ComposableContainerGui` instead of `thaumcraft.client.gui.GuiResearchTable`.

**Consequence for us:** the live GUI in GTNH is `ComposableContainerGui`, assembled by
`ResearchTableGuiFactory`. Our button + ghost overlay + spinner must be injected there.

## GUI component system (where the button/overlay live)

- `elan.tweaks.common.gui.ComposableContainerGui` — composes a list of `UIComponent`s.
- Component interfaces: `UIComponent`, `BackgroundUIComponent`, `ForegroundUIComponent`,
  `ClickableUIComponent`, `MouseOverUIComponent`, `TickingUIComponent`, `UIContext`.
- `…table.gui.ResearchTableGuiFactory` — **assembles** the components for the table screen.
  Injection target: append our components at the tail of the factory's `create(...)`.
- **Precedent for a clickable button:** `…table.gui.component.CopyButtonUIComponent` — copy this
  pattern for `SolveButtonUIComponent`.
- Hex pixel mapping (resizable GUI — reuse, don't reinvent — Variant 1):
  `…table.gui.layout.ParchmentHexMapLayout` (+ inner `Hex`), `…common.gui.layout.hex.HexLayout`,
  `HexLayoutResearchNoteDataAdapter`. Our ghost overlay maps hex `(q,r) -> pixel` via these so it
  stays aligned at any window size.
- Aspect rendering: `…common.gui.rendering.AspectRenderer`, `RuneTexture`, fx `OrbParticle`,
  `LineParticle` (reuse for ghost styling).

## Adapters we can reuse or mirror (the "required ports")

```
ResearchNotesAdapter(player, TileResearchTable, Container)
  .getData(): ResearchNoteData          // read note
  .write(hexKey, aspect)                 // -> sendAspectPlacePacket -> PacketAspectPlaceToServer
  .erase(hexKey)
  .findUsedAspectAmounts(): Map<Aspect,Int>
  HexType { VACANT, ROOT, NODE }         // meaning of HexEntry.type

AspectPoolAdapter(player, TileResearchTable)            // implements AspectPool
  .hasDiscovered(a) / .allDiscovered()
  .amountOf(a) / .bonusAmountOf(a) / .totalAmountOf(a)  // -> solver Inventory.supply
  .anyComponentMissingFor(a)
  .contains(Map<Aspect,Int>) / .missing(Map<Aspect,Int>)

AspectCombinerAdapter(player, TileResearchTable)
  .combine(a, b)                         // -> PacketAspectCombinationToServer

ScribeToolsAdapter   -> ScribeTools.areMissingOrEmpty()  // ink gate
PlayerInventoryAdapter
KnowledgeBaseAdapter
```

## Domain model worth reading (overlaps our solver concepts)

- `AspectTree` (singleton): `areRelated(a,b)` = TC adjacency rule; `orderOf`, `allOrderLeaning`,
  `allEntropyLeaning`, `findBalance` — their layout/sorting, not a path solver.
- `AspectPallet`: `combine`, `combineBatch`, `derive`, `deriveBatch`, `isDrainedOf` — pool ops we
  drive when applying a solution (combine missing compounds).
- `ResearchProcess`: `write`, `erase`, `complete`, `getUsedAspectAmounts`, `getRequiresInkToContinue`,
  `shouldObfuscate` — the manual-edit use cases; our auto-apply is a sibling of `write`.

## Integration strategy summary

1. Read inputs on the client thread via these adapters: note (`ResearchNotesAdapter`), pool amounts
   (`AspectPoolAdapter`), ink (`ScribeToolsAdapter`), grid shape (`ResearchNoteData.hexes`).
2. Snapshot -> background solver (our Kotlin port).
3. Preview: `ForegroundUIComponent` ghost overlay positioned via `ParchmentHexMapLayout`.
4. Apply: `AspectCombinerAdapter.combine(...)` for missing compounds, then `ResearchNotesAdapter
   .write(...)` (or raw `PacketAspectPlaceToServer`) per cell; post-verify by re-reading the note.

---

## CONFIRMED SIGNATURES (Phase 3, `javap` on `reference/jars/ThaumcraftResearchTweaks-1.3.0.jar`)

Pinned 2026-06-15 (Task 3.1). **Real package** is
`elan.tweaks.thaumcraft.research.frontend.…` (the earlier `elan.tweaks.thaumcraft.adapters.…`
guess was wrong).

### Required ports we consume (read-side) — `…frontend.domain.ports.required`
```kotlin
interface AspectPool {                       // …ports.required.AspectPool
  fun hasDiscovered(a: Aspect): Boolean
  fun allDiscovered(): Array<Aspect>
  fun amountOf(a: Aspect): Int
  fun bonusAmountOf(a: Aspect): Int
  fun totalAmountOf(a: Aspect): Int          // == amountOf + bonusAmountOf; use this for supply
  fun anyComponentMissingFor(a: Aspect): Boolean
  fun missing(demand: Map<Aspect,Int>): Boolean
  fun contains(demand: Map<Aspect,Int>): Boolean
}
interface ScribeTools { fun areMissingOrEmpty(): Boolean }   // …ports.required.ScribeTools
```

### Adapters (live impls) — `…frontend.integration.adapters`
```kotlin
class AspectPoolAdapter(player: EntityPlayer, tile: TileResearchTable) : AspectPool
class ScribeToolsAdapter(tile: TileResearchTable) : ScribeTools
class AspectCombinerAdapter(player: EntityPlayer, tile: TileResearchTable) : AspectCombiner
class ResearchNotesAdapter(player: EntityPlayer, tile: TileResearchTable, container: Container) : ResearchNotes {
  fun getData(): ResearchNoteData          // <-- read live note (or use ResearchManager.getData directly)
  fun findUsedAspectAmounts(): Map<Aspect,Int>
  // write/erase/duplicate/combine return Kotlin Result<T> -> JVM-mangled names
  // (write-gIAlu-s, erase-IoAF18A, combine-gIAlu-s). AWKWARD from Kotlin: we DON'T call these;
  // the Applier sends TC PacketAspectPlaceToServer / PacketAspectCombinationToServer directly.
  object HexType { const val VACANT = 0; const val ROOT = 1; const val NODE = 2 }  // == HexEntry.type
}
```
**Phase 3 plan:** InventoryReader takes an `AspectPool` (caller builds `AspectPoolAdapter(player,tile)`)
and reads `totalAmountOf` per `data.universe` gated by `hasDiscovered`. Applier gates apply on
`ScribeToolsAdapter(tile).areMissingOrEmpty()`. BoardReader reads the note via
`ResearchManager.getData(note)` directly (avoids the mangled adapter API).

### GUI seam (mostly Phase 4 — FQNs pinned now) — `…frontend.integration.table.gui`
- Factory: `…table.gui.ResearchTableGuiFactory` → builds `elan.tweaks.common.gui.ComposableContainerGui`.
- Button precedent: `…table.gui.component.CopyButtonUIComponent`.
- Component ifaces: `elan.tweaks.common.gui.component.{UIComponent, BackgroundUIComponent,
  ForegroundUIComponent, ClickableUIComponent, MouseOverUIComponent, TickingUIComponent, UIContext}`.
- Hex→pixel: `…table.gui.layout.ParchmentHexMapLayout` (+ inner `Hex`),
  `elan.tweaks.common.gui.layout.hex.HexLayout`, `…table.gui.layout.HexLayoutResearchNoteDataAdapter`.

---

## CONFIRMED GUI SEAM (Phase 4, Task 4.1 — `javap`/`javap -c`, 2026-06-15)

### Injection target & strategy (DECIDED)
`ResearchTableGuiFactory` is a Kotlin `object` (singleton `INSTANCE`):
```kotlin
fun create(player: EntityPlayer, tile: TileResearchTable): ComposableContainerGui
```
`create` builds a component list via chained `CollectionsKt.plus(...)` and passes it to
`ComposableContainerGui.Companion.gui(scale, container, list, fn)`. The returned gui stores
components in **mutable `private final ArrayList`s** (verified in the ctor bytecode — each is
`new ArrayList()` then populated by interface): `backgrounds`, `foregrounds`, `tickables`,
`mouseOverables`, `clickables` (+ dragndrop lists). The ctor categorizes each passed `UIComponent`
into every list whose interface it implements.

**Mixin plan (Task 4.7):**
1. `@Mixin(ComposableContainerGui::class)` **accessor interface** exposing the mutable lists:
   `@Accessor("clickables") fun getClickables(): MutableList<ClickableUIComponent>` (+ `foregrounds`,
   `backgrounds`, `tickables`, `mouseOverables`).
2. `@Mixin(ResearchTableGuiFactory::class)`, `@Inject(method = "create", at = @At("RETURN"))`,
   handler params `(EntityPlayer player, TileResearchTable tile, CallbackInfoReturnable<ComposableContainerGui> cir)`.
   Build one shared `SolveController` + our 3 components, then add each to the matching list(s) on
   `cir.returnValue` cast to the accessor interface. (Container, if needed, = `gui.inventorySlots`
   from `GuiContainer`; layout = `ParchmentHexMapLayout.INSTANCE`; player/tile from args.)
   This matches the plan's preferred RETURN-append approach; lists are mutable so no redirect needed.
   Register both in `mixins.tcresearchsolver.json` `"client"`.

### Component interfaces (`elan.tweaks.common.gui.component`) — exact methods
`UIComponent` is an **empty marker**. Sub-interfaces (each extends UIComponent):
```kotlin
BackgroundUIComponent.onDrawBackground(mouse: VectorXY, partialTicks: Float, ctx: UIContext)
ForegroundUIComponent.onDrawForeground(mouse: VectorXY, scale: Scale, ctx: UIContext)
ClickableUIComponent.onMouseClicked(mouse: VectorXY, button: MouseButton, ctx: UIContext)
MouseOverUIComponent.onMouseOver(mouse: VectorXY, partialTicks: Float, ctx: UIContext)
TickingUIComponent.onTick(partialTicks: Float, ctx: UIContext)
```
**Precedent — `CopyButtonUIComponent`** implements `Background + MouseOver + Clickable + Ticking`;
ctor `(Rectangle bounds, VectorXY requirementsUiOrigin, ResearchProcessPort, ResearcherKnowledgePort, AspectsTreePort)`.
→ `SolveButtonUIComponent` should implement the same 4 interfaces; ctor takes `(Rectangle bounds, SolveController)`.
Spinner = `Ticking + Foreground`; Ghost = `Foreground`.

### `UIContext` drawing API (what components call to render)
```kotlin
drawTag(aspect: Aspect, alpha: Int, pos: VectorXY)               // aspect icon, alpha 0..255-ish → GHOST
drawTag(aspect, alpha, w, h, scaleF: Float, pos)                 // sized variant
drawTagMonochrome(aspect, scaleF: Float, pos)
drawWithShadow(text: String, pos: VectorXY)                      // text
drawTooltip(pos, vararg lines: String); drawTooltipCentered(...); drawTooltipVerticallyCentered(...)
drawOrb(pos[, color: Int]); drawLine(a: VectorXY, b: VectorXY)
drawBlending(tex: TextureInstance, pos: VectorXY, color: Rgba)   // textured quad (button bg)
playSound(name: String, volume: Float, pitch: Float, repeat: Boolean); getRandom(): Random
```

### DTOs (`elan.tweaks.common.gui.dto`, `…peripheral`)
- `VectorXY` is an **interface**: `getX():Int`, `getY():Int`, `plus/minus(VectorXY|Int)`, `times(Double)`.
  Concrete impls `Vector2D`/`Vector3D` (`Vector2D.Companion.getZERO()`); construct via those.
- `Scale(width: Int, height: Int)` — getWidth/getHeight.
- `Rgba(r,g,b,a: Float)` — for `drawBlending`.
- `Rectangle(origin: VectorXY, scale: Scale)` — `contains(VectorXY)`, `getOrigin()`, `getScale()`, `plus(VectorXY)`. Button bounds + hit-test.
- `MouseButton` (abstract; `isDown(): Boolean`, `Companion`).

### Hex → pixel (ghost overlay positioning)
`ParchmentHexMapLayout.INSTANCE` holds a **private static** `keyToHex: Map<String, Hex>` where
`Hex(origin: VectorXY, center: VectorXY)` (`getCenter()` = pixel center). No public lookup — expose
via an `@Accessor` mixin (`@Accessor("keyToHex") fun getKeyToHex(): Map<String, Hex>`) or reflection.
⚠️ **Confirm at runClient**: that the map key equals our solver hexKey `"q,r"` and that `getCenter()`
aligns at multiple window sizes (the live note area is rendered via `HexLayoutResearchNoteDataAdapter`,
which `ParchmentHexMapLayout` mirrors). Ghost = least-critical cosmetic; tune placement in Phase 5.

### @Mod dependencies / coremod (already correct in TcResearchSolverMod.kt)
RT `mcmod.info` modid = **`ThaumcraftResearchTweaks`** (capitalized); manifest `TweakClass` =
SpongeMixins (`spongemixins`), `ForceLoadAsMod: true`. Our `@Mod(dependencies = "required-after:forgelin;
required-after:spongemixins;required-after:Thaumcraft;required-after:ThaumcraftResearchTweaks")` matches.
