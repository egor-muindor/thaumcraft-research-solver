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
