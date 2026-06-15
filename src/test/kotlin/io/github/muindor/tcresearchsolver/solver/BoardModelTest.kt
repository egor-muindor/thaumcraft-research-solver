package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoardModelTest {

    @Test fun `creates a board where every on-board cell defaults to EMPTY`() {
        val b = createBoard(2)
        assertEquals(CellState.Empty, getState(b, Hex(0, 0)))
        assertEquals(CellState.Empty, getState(b, Hex(2, 0)))
    }

    @Test fun `throws for off-board access`() {
        val b = createBoard(2)
        assertThrows(IllegalArgumentException::class.java) { getState(b, Hex(3, 0)) }
    }

    @Test fun `stores and reads back states`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("void", false))
        setState(b, Hex(-1, 0), CellState.Dead)
        assertEquals(CellState.Anchor("air"), getState(b, Hex(0, 0)))
        assertEquals(listOf(Hex(0, 0)), anchorCells(b).map { it.first })
        assertEquals(listOf("air", "void"), filledCells(b).map { it.aspect }.sorted())
    }

    @Test fun `setting EMPTY clears a stored cell`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Placed("air", true))
        setState(b, Hex(0, 0), CellState.Empty)
        assertEquals(CellState.Empty, getState(b, Hex(0, 0)))
    }

    @Test fun `createBoard rejects radius out of range`() {
        assertThrows(IllegalArgumentException::class.java) { createBoard(1) }
        assertThrows(IllegalArgumentException::class.java) { createBoard(6) }
        assertThrows(IllegalArgumentException::class.java) { createBoard(0) }
    }

    @Test fun `filledNeighbors returns only filled on-board neighbors`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("void", false))
        // (0,0) has 6 neighbors; only (1,0) is filled and on board
        val neighbors = filledNeighbors(b, Hex(0, 0))
        assertEquals(1, neighbors.size)
        assertEquals("void", neighbors[0].aspect)
    }
}
