package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HeuristicTest {

    private val data = buildAspectData()

    @Test
    fun `is (0,0) when all anchors are already connected`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("void", false))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        val inv = makeInventory(listOf("air" to 100, "entropy" to 100), DEFAULT_THRESHOLD)
        val h = remainderHeuristic(data, b, inv)
        assertEquals(0.0, h.cells, "cells should be 0")
        assertEquals(0.0, h.scarcity, "scarcity should be 0")
    }

    @Test
    fun `needs at least 1 inner cell for two anchors at distance 2`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        val inv = makeInventory(listOf("air" to 100), DEFAULT_THRESHOLD)
        val h = remainderHeuristic(data, b, inv)
        assertTrue(h.cells >= 1.0, "cells should be >= 1 but was ${h.cells}")
    }

    @Test
    fun `routes around dead hexes - no path gives Infinity scarcity`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        // wall off (2,0) entirely with DEAD neighbors so it cannot be reached
        for (n in listOf(Hex(1, 0), Hex(2, -1), Hex(1, 1))) setState(b, n, CellState.Dead)
        val inv = makeInventory(listOf("air" to 100), DEFAULT_THRESHOLD)
        val h = remainderHeuristic(data, b, inv)
        assertEquals(Double.POSITIVE_INFINITY, h.scarcity, "scarcity should be +Infinity when no path exists")
    }

    @Test
    fun `does not treat a locked-only island as a terminal (h stays finite or small)`() {
        val b = createBoard(3)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("fire"))
        setState(b, Hex(-3, 3), CellState.Placed("water", true)) // far island, locked but no anchor
        val inv = makeInventory(listOf("air" to 100), DEFAULT_THRESHOLD)
        val h = remainderHeuristic(data, b, inv)
        // island must NOT force a long connection; with anchors adjacent, hCells should be 0
        assertEquals(0.0, h.cells, "cells should be 0 when anchors are adjacent")
    }
}
