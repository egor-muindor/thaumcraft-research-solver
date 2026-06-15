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
