package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [toBoard] — the pure mapper from note data to solver [Board].
 *
 * IMPORTANT: This test NEVER calls [BoardReader.read] — that method touches TC classes
 * (ResearchManager, HexUtils, etc.) which are not available in a unit-test JVM.
 * Only the pure [toBoard] function is exercised here.
 */
class BoardReaderTest {

    // ----- helpers -----

    /**
     * Build the full set of 19 R2 hexagon cell keys ("q,r") → Pair(q,r).
     * boardCells(2) returns all axial hexes with distance <= 2 from origin.
     */
    private fun r2NoteHexes(): Map<String, Pair<Int, Int>> =
        boardCells(2).associate { h -> hexKey(h) to Pair(h.q, h.r) }

    // ----- main scenario: two ROOTs + one NODE -----

    @Test fun `two ROOT entries produce two Anchors with correct aspects`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0"  to NoteEntry("ignis", HexType.ROOT),
            "1,0"  to NoteEntry("aer",   HexType.ROOT),
        )
        val board = toBoard(noteHexes, entries, radius = 2)

        assertEquals(CellState.Anchor("ignis"), getState(board, Hex(0, 0)))
        assertEquals(CellState.Anchor("aer"),   getState(board, Hex(1, 0)))
    }

    @Test fun `one NODE entry produces a locked Placed cell`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0"  to NoteEntry("ignis", HexType.ROOT),
            "1,0"  to NoteEntry("aer",   HexType.ROOT),
            "0,1"  to NoteEntry("terra", HexType.NODE),
        )
        val board = toBoard(noteHexes, entries, radius = 2)

        val state = getState(board, Hex(0, 1))
        assertTrue(state is CellState.Placed, "Expected Placed but got $state")
        state as CellState.Placed
        assertEquals("terra", state.aspect)
        assertTrue(state.locked, "NODE-derived Placed must have locked = true")
    }

    @Test fun `synthetic R2 note two ROOTs plus one NODE board has exactly those three filled cells`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0"  to NoteEntry("ignis", HexType.ROOT),
            "1,0"  to NoteEntry("aer",   HexType.ROOT),
            "0,1"  to NoteEntry("terra", HexType.NODE),
        )
        val board = toBoard(noteHexes, entries, radius = 2)

        // All 19 R2 hexes are in noteHexes, so none should be Dead.
        // Only the 3 entry cells should be non-Empty.
        for (h in boardCells(2)) {
            val key = hexKey(h)
            val state = getState(board, h)
            when (key) {
                "0,0"  -> assertEquals(CellState.Anchor("ignis"), state)
                "1,0"  -> assertEquals(CellState.Anchor("aer"),   state)
                "0,1"  -> assertEquals(CellState.Placed("terra", locked = true), state)
                else   -> assertEquals(CellState.Empty, state, "Expected Empty at $key")
            }
        }
    }

    // ----- VACANT entry stays Empty -----

    @Test fun `VACANT entry cell stays Empty`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0" to NoteEntry(null,     HexType.VACANT),
            "1,0" to NoteEntry("ignis",  HexType.ROOT),
        )
        val board = toBoard(noteHexes, entries, radius = 2)

        assertEquals(CellState.Empty, getState(board, Hex(0, 0)))
        assertEquals(CellState.Anchor("ignis"), getState(board, Hex(1, 0)))
    }

    // ----- absent entry cell stays Empty -----

    @Test fun `cell in noteHexes with no entry stays Empty`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "1,0" to NoteEntry("ignis", HexType.ROOT),
        )
        val board = toBoard(noteHexes, entries, radius = 2)

        // "0,0" has no entry — should be Empty
        assertEquals(CellState.Empty, getState(board, Hex(0, 0)))
    }

    // ----- cell NOT in noteHexes becomes Dead -----

    @Test fun `radius-board cell absent from noteHexes becomes Dead`() {
        // Use only the center hex in noteHexes; all other R2 board cells should be Dead.
        val noteHexes = mapOf("0,0" to Pair(0, 0))
        val entries = mapOf(
            "0,0" to NoteEntry("ignis", HexType.ROOT),
        )
        val board = toBoard(noteHexes, entries, radius = 2)

        // (1,0) is on the R2 board but NOT in noteHexes → Dead
        assertEquals(CellState.Dead, getState(board, Hex(1, 0)))
        assertEquals(CellState.Dead, getState(board, Hex(0, 1)))
    }

    @Test fun `only cells in noteHexes are non-Dead when noteHexes is a partial shape`() {
        val onlyCenterAndOneNeighbor = mapOf(
            "0,0"  to Pair(0, 0),
            "1,0"  to Pair(1, 0),
        )
        val entries = emptyMap<String, NoteEntry>()
        val board = toBoard(onlyCenterAndOneNeighbor, entries, radius = 2)

        // noteHexes cells with no entry → Empty
        assertEquals(CellState.Empty, getState(board, Hex(0, 0)))
        assertEquals(CellState.Empty, getState(board, Hex(1, 0)))

        // All other R2 board cells → Dead
        for (h in boardCells(2)) {
            val key = hexKey(h)
            if (key == "0,0" || key == "1,0") continue
            assertEquals(CellState.Dead, getState(board, h), "Expected Dead at $key")
        }
    }

    // ----- error: ROOT/NODE with null aspectTag -----

    @Test fun `ROOT entry with null aspectTag throws IllegalArgumentException`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0" to NoteEntry(null, HexType.ROOT),
        )
        assertThrows(IllegalArgumentException::class.java) {
            toBoard(noteHexes, entries, radius = 2)
        }
    }

    @Test fun `NODE entry with null aspectTag throws IllegalArgumentException`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0" to NoteEntry(null, HexType.NODE),
        )
        assertThrows(IllegalArgumentException::class.java) {
            toBoard(noteHexes, entries, radius = 2)
        }
    }

    // ----- locked flag is true for NODE-derived Placed (explicit) -----

    @Test fun `NODE-derived Placed has locked = true`() {
        val noteHexes = r2NoteHexes()
        val entries = mapOf(
            "0,0" to NoteEntry("aqua", HexType.NODE),
        )
        val board = toBoard(noteHexes, entries, radius = 2)
        val state = getState(board, Hex(0, 0)) as CellState.Placed
        assertTrue(state.locked)
    }
}
