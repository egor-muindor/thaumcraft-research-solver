# Thaumcraft integration map (GTNH 2.8.4)

Reference extracted from `reference/jars/Thaumcraft-1.7.10-4.2.3.5a.jar` via `javap`.
Everything below is reachable from an addon **without modifying Thaumcraft source** — public
API plus accessible internals. Coordinates use axial `(q, r)`, identical to the TS solver's `Hex`.

## Aspects (`thaumcraft.api.aspects`)

```java
class Aspect {
  public static LinkedHashMap<String, Aspect> aspects;   // every registered aspect (all GTNH mods)
  public static Aspect getAspect(String tag);
  public Aspect[] getComponents();   // length 0 (primal) or 2 (compound)
  public boolean isPrimal();
  public static ArrayList<Aspect> getPrimalAspects();
  public static ArrayList<Aspect> getCompoundAspects();
  public String getTag();            // stable id, e.g. "ignis", "aer"
  public int getColor();
}

class AspectList {                   // used for player's discovered amounts
  public LinkedHashMap<Aspect,Integer> aspects;
  public int getAmount(Aspect a);
  public Aspect[] getAspects();
}
```

**Building the solver's aspect graph live (covers every mod automatically):**
- `combinations[compound] = (components[0], components[1])` from `Aspect.getComponents()`.
- `adjacency`: `a ~ b` iff `b ∈ a.getComponents()` or `a ∈ b.getComponents()`.
  This is exactly Thaumcraft's "two adjacent placed aspects are valid if one is a component of
  the other" rule (ResearchTweaks encodes the same thing in `AspectTree.areRelated`).
- `primals` = `Aspect.getPrimalAspects()`.

## Research note state (`thaumcraft.common.lib.research`)

```java
ResearchManager.getData(ItemStack note) -> ResearchNoteData          // public static
ResearchManager.updateData(ItemStack note, ResearchNoteData)         // public static (server writes)

class ResearchNoteData {
  public String key;
  public int color;
  public HashMap<String, HexUtils.Hex>            hexes;       // grid shape; key = "q,r"
  public HashMap<String, ResearchManager.HexEntry> hexEntries; // placed cells; key = "q,r"
  public boolean complete;
  public int copies;
  public boolean isComplete();
}

class ResearchManager.HexEntry { public Aspect aspect; public int type; }
```

`HexEntry.type` values (named by ResearchTweaks `ResearchNotesAdapter.HexType`):
- `ROOT` — the required anchor aspects pre-placed on the note (the endpoints to connect).
- `NODE` — a path aspect the player has written.
- `VACANT` — empty (no entry / placeholder).
  > Confirm the exact int constants during implementation (javap the enum or read the note in dev).

```java
class HexUtils.Hex { public int q, r; Hex(int q,int r); Hex getNeighbour(int dir);
                     CubicHex toCubicHex(); Pixel toPixel(int size); }
HexUtils.getDistance(Hex,Hex); HexUtils.generateHexes(int radius); // static helpers
```

## Combination / reduction helpers (`ResearchManager`, public static)

```java
Aspect      getCombinationResult(Aspect a, Aspect b);   // compound produced by combining a+b
AspectList  reduceToPrimals(AspectList);
boolean     checkResearchCompletion(ItemStack note, ResearchNoteData, String player);
```

## Network — how placement/combination actually happen

The GUI never mutates the note directly; it sends packets to the server, which validates
(aspect availability, ink, adjacency), updates the note, and syncs back. An addon does the same.

```java
class PacketHandler { public static final SimpleNetworkWrapper INSTANCE; } // send channel

// public constructor — place aspect at hex (q,r) on the table at (x,y,z):
new PacketAspectPlaceToServer(EntityPlayer player, byte q, byte r, int x, int y, int z, Aspect aspect);

// public constructor — combine two aspects in the working pool:
new PacketAspectCombinationToServer(EntityPlayer player, int x, int y, int z,
                                    Aspect a1, Aspect a2, boolean ab1, boolean ab2, boolean ab3);

PacketHandler.INSTANCE.sendToServer(packet);
```

Other relevant packets (server -> client sync): `PacketSyncAspects`, `PacketSyncResearch`,
`PacketAspectPool`, `PacketResearchComplete`. Re-read `ResearchManager.getData(note)` after a
short delay to verify the server accepted the writes (post-verify).

## Table tile / GUI (vanilla TC — note: GTNH replaces the GUI, see researchtweaks-map.md)

```
thaumcraft.common.tiles.TileResearchTable          // table block entity (gives x,y,z)
thaumcraft.common.container.ContainerResearchTable
thaumcraft.client.gui.GuiResearchTable             // VANILLA gui — NOT used in GTNH
thaumcraft.api.IScribeTools                         // ink item interface
```

## Player knowledge / scribe tools

- Discovered aspect amounts come from the player's Thaumcraft knowledge (read via TC's player
  knowledge API / the table's aspect pool — ResearchTweaks wraps this in `AspectPoolAdapter`).
- Scribing tools (ink) presence — ResearchTweaks wraps this in `ScribeToolsAdapter`
  (`areMissingOrEmpty()`); back it with TC's `IScribeTools` / table inventory.

---

## CONFIRMED SIGNATURES (Phase 3, `javap` on `reference/jars/Thaumcraft-1.7.10-4.2.3.5a.jar`)

Pinned 2026-06-15 (Task 3.1). Supersedes the speculative paths above where they differ.

### Corrections to earlier notes
- `ResearchNoteData` is at **`thaumcraft.common.lib.research.ResearchNoteData`** (NOT `thaumcraft.api.research`).
- Two `HexUtils` exist in the jar; the one referenced by `ResearchNoteData.hexes` is
  **`thaumcraft.common.lib.utils.HexUtils`** (the canonical one). Ignore `thaumcraft.common.lib.HexUtils`.
- Packets are under **`thaumcraft.common.lib.network.playerdata`**.

### Aspect / AspectList (`thaumcraft.api.aspects`) — confirmed exact
```java
public static LinkedHashMap<String,Aspect> Aspect.aspects;     // all registered aspects (every mod)
public String  Aspect.getTag();
public Aspect[] Aspect.getComponents();                        // length 0 (primal) or 2 (compound)
public boolean Aspect.isPrimal();
public int     Aspect.getColor();
public static Aspect Aspect.getAspect(String tag);
public static ArrayList<Aspect> Aspect.getPrimalAspects();     // 48 primals known incl. GTNH
public static ArrayList<Aspect> Aspect.getCompoundAspects();
// (Aspect.components field is package-private; use getComponents())
public LinkedHashMap<Aspect,Integer> AspectList.aspects;       // player discovered amounts
public int AspectList.getAmount(Aspect);
public Aspect[] AspectList.getAspects();
```

### Research note (`thaumcraft.common.lib.research`) — confirmed exact
```java
public class ResearchNoteData {
  public String key; public int color;
  public HashMap<String, ResearchManager$HexEntry>      hexEntries; // placed/special cells, key "q,r"
  public HashMap<String, thaumcraft.common.lib.utils.HexUtils$Hex> hexes; // grid SHAPE, key "q,r"
  public boolean complete; public int copies;
  public boolean isComplete();
}
public class ResearchManager$HexEntry { public Aspect aspect; public int type; HexEntry(Aspect, int); }
```
**`HexEntry.type` int constants (from RT `ResearchNotesAdapter$HexType`, `const val`):**
`VACANT = 0`, `ROOT = 1`, `NODE = 2`.  (ROOT = pre-placed anchors to connect; NODE = player-written
path cell; VACANT/absent = empty.) Runtime-verify against a real note in Phase 5.

`HexUtils$Hex { public int q; public int r; Hex(int,int); }` — axial, identical to solver `Hex`.
`static HashMap<String,Hex> HexUtils.generateHexes(int radius)`; `static int HexUtils.getDistance(Hex,Hex)`.

### ResearchManager static helpers — confirmed exact
```java
public static ResearchNoteData ResearchManager.getData(ItemStack note);
public static void             ResearchManager.updateData(ItemStack note, ResearchNoteData);   // server writes
public static Aspect           ResearchManager.getCombinationResult(Aspect a, Aspect b);
public static AspectList       ResearchManager.reduceToPrimals(AspectList);
public static boolean          ResearchManager.checkResearchCompletion(ItemStack, ResearchNoteData, String player);
```

### Network packets (`thaumcraft.common.lib.network[.playerdata]`) — confirmed exact
```java
public static final SimpleNetworkWrapper PacketHandler.INSTANCE;   // .sendToServer(IMessage)

// place aspect at hex (q,r) on table at (x,y,z):
public PacketAspectPlaceToServer(EntityPlayer player, byte q, byte r, int x, int y, int z, Aspect aspect);

// combine a1+a2 in the working pool at table (x,y,z):
public PacketAspectCombinationToServer(EntityPlayer player, int x, int y, int z,
                                       Aspect a1, Aspect a2, boolean ab1, boolean ab2, boolean ab3);
```
**Combine boolean semantics (decompiled `onMessage` + RT `AspectCombinerAdapter`):** the server
proceeds only if `(getAspectPoolFor(a1) > 0 || ab1) && (getAspectPoolFor(a2) > 0 || ab2)`, where
`getAspectPoolFor` reads the **player's personal aspect pool only**. During consumption, each
component is taken from the personal pool if `getAspectPoolFor(a) > 0`, **else from the table's
`bonusAspects` AspectList when `abN` is set**. So `abN` means "allow drawing component N from the
table bonus pool." **`ab3` is a dead parameter** — the ctor never stores it.
→ **Mirror RT exactly:** `ab1 = tile.bonusAspects.getAmount(a1) > 0`,
`ab2 = tile.bonusAspects.getAmount(a2) > 0`, `ab3 = true` (RT passes true; irrelevant since dead).
RT's `AspectCombinerAdapter.combine` constructs the packet precisely this way (verified via `javap -c`).
> ⚠️ Do NOT hard-code `false,false,false`: our solver inventory uses `AspectPool.totalAmountOf`
> (personal **+** bonus), so a feasible plan can rely on a component that only exists in the bonus
> pool; `abN=false` would make the server silently reject that combine.

Both packets derive `dim`/`playerid` from the `EntityPlayer` internally. `(x,y,z)` = the
`TileResearchTable`'s `xCoord/yCoord/zCoord` (inherited from `net.minecraft.tileentity.TileEntity`,
public). Map solver tag → TC `Aspect` via `Aspect.getAspect(tag)`.

`thaumcraft.common.tiles.TileResearchTable extends thaumcraft.api.TileThaumcraft implements IInventory`
(has `public AspectList bonusAspects`).
