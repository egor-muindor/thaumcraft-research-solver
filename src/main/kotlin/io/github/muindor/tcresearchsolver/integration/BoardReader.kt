package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.*

// ---------------------------------------------------------------------------
// Pure data types — no TC imports; unit-testable
// ---------------------------------------------------------------------------

/**
 * The type of a hex cell as encoded in [thaumcraft.common.lib.research.ResearchManager.HexEntry].
 *
 * Confirmed int constants (RT `ResearchNotesAdapter$HexType`, `javap`, 2026-06-15):
 *   VACANT = 0, ROOT = 1, NODE = 2
 */
enum class HexType { VACANT, ROOT, NODE }

/**
 * TC-free representation of one [thaumcraft.common.lib.research.ResearchManager.HexEntry].
 *
 * @param aspectTag the aspect's stable string id (e.g. "ignis"), or null when the entry has
 *                  no aspect (only valid for VACANT; ROOT/NODE without a tag = malformed note).
 * @param type      the decoded [HexType].
 */
data class NoteEntry(val aspectTag: String?, val type: HexType)

// ---------------------------------------------------------------------------
// Pure mapper — toBoard
// ---------------------------------------------------------------------------

/**
 * Maps a research note's hex data to a solver [Board].
 *
 * @param noteHexes  the full shape of the note (`ResearchNoteData.hexes`), key = "q,r" → Pair(q,r).
 *                   Every key in this map is a valid board cell for this note.
 * @param entries    the placed / special cells (`ResearchNoteData.hexEntries`), key = "q,r" → [NoteEntry].
 *                   Keys missing from [noteHexes] are silently skipped (defensive).
 * @param radius     the board radius (2..5), derived from the max distance of any hex in [noteHexes]
 *                   from the origin.
 * @return a [Board] with:
 *   - [CellState.Dead] for every on-board cell absent from [noteHexes]
 *   - [CellState.Anchor] for ROOT entries (fixed, must be connected)
 *   - [CellState.Placed] (locked=true) for NODE entries (already-written path cells)
 *   - [CellState.Empty] for VACANT entries and cells in [noteHexes] with no entry
 * @throws IllegalArgumentException if a ROOT or NODE entry has a null aspectTag (malformed note),
 *         or if [radius] is outside 2..5 (delegated to [createBoard]).
 */
fun toBoard(
    noteHexes: Map<String, Pair<Int, Int>>,
    entries: Map<String, NoteEntry>,
    radius: Int,
): Board {
    val board = createBoard(radius)  // throws if radius !in 2..5

    // Step 1: mark every on-board cell that is NOT in noteHexes as Dead.
    for (h in boardCells(radius)) {
        val key = hexKey(h)
        if (!noteHexes.containsKey(key)) {
            setState(board, h, CellState.Dead)
        }
    }

    // Step 2: apply entries (ROOT → Anchor, NODE → Placed(locked=true), VACANT → leave Empty).
    for ((key, entry) in entries) {
        // Skip entries whose key is not part of the note's shape (defensive).
        val coords = noteHexes[key] ?: continue

        val h = Hex(coords.first, coords.second)
        when (entry.type) {
            HexType.ROOT -> {
                val tag = entry.aspectTag
                    ?: throw IllegalArgumentException("ROOT entry at $key has null aspectTag (malformed note)")
                setState(board, h, CellState.Anchor(tag))
            }
            HexType.NODE -> {
                val tag = entry.aspectTag
                    ?: throw IllegalArgumentException("NODE entry at $key has null aspectTag (malformed note)")
                setState(board, h, CellState.Placed(tag, locked = true))
            }
            HexType.VACANT -> {
                // VACANT: leave as Empty (no-op; createBoard starts all cells as Empty)
            }
        }
    }

    return board
}

// ---------------------------------------------------------------------------
// TC-touching layer — BoardReader.read
// Keep TC imports below this line so the pure mapper above is unit-testable.
// ---------------------------------------------------------------------------

/** Converts the raw [thaumcraft.common.lib.research.ResearchManager.HexEntry.type] int to [HexType]. */
@Suppress("FunctionName")
internal fun hexTypeOf(t: Int): HexType = when (t) {
    1    -> HexType.ROOT
    2    -> HexType.NODE
    else -> HexType.VACANT   // 0 = VACANT; any unknown int → VACANT (defensive)
}

/**
 * Reads a live research note item and produces a solver [Board].
 *
 * **Only call in-game** — this method invokes TC's [thaumcraft.common.lib.research.ResearchManager]
 * which is not populated in unit tests.
 */
object BoardReader {
    fun read(note: net.minecraft.item.ItemStack): Board {
        val data = thaumcraft.common.lib.research.ResearchManager.getData(note)

        // noteHexes: "q,r" → Pair(q, r)
        val noteHexes: Map<String, Pair<Int, Int>> = data.hexes.mapValues { (_, h) ->
            Pair(h.q, h.r)
        }

        // entries: "q,r" → NoteEntry
        val entries: Map<String, NoteEntry> = data.hexEntries.mapValues { (_, e) ->
            NoteEntry(
                aspectTag = e.aspect?.tag,
                type = hexTypeOf(e.type),
            )
        }

        // radius = max axial distance from origin to any note hex
        val radius = noteHexes.values.maxOf { (q, r) -> distance(Hex(0, 0), Hex(q, r)) }

        return toBoard(noteHexes, entries, radius)
    }
}
