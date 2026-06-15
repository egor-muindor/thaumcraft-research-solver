package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoardValidateTest {

    private val data = buildAspectData()

    @Test fun `valid two anchors joined by a single valid chain`() {
        val b = createBoard(2)
        // air(0,0) - void(1,0) ... void=air+entropy so air-void valid; entropy anchor at (2,0): void-entropy valid
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("void", false))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        val v = validate(data, b)
        assertTrue(v.valid)
        assertTrue(allAnchorsConnected(b))
        assertTrue(isComplete(data, b))
    }

    @Test fun `SAME_ASPECT_ADJACENT when identical aspects touch`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("air", false))
        val v = validate(data, b)
        assertFalse(v.valid)
        assertTrue(v.errors.any { it.type == ValidationErrorType.SAME_ASPECT_ADJACENT })
    }

    @Test fun `INVALID_LINK when adjacent aspects are not graph-connected`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("earth", false))  // air-earth not an edge
        val v = validate(data, b)
        assertTrue(v.errors.any { it.type == ValidationErrorType.INVALID_LINK })
    }

    @Test fun `detects an incidental invalid touch between two separate branches`() {
        val b = createBoard(2)
        // Place two valid pairs that happen to touch invalidly.
        setState(b, Hex(0, 0), CellState.Placed("air", false))
        setState(b, Hex(0, 1), CellState.Placed("earth", false))  // adjacency (0,0)-(0,1) invalid
        val v = validate(data, b)
        assertFalse(v.valid)
    }

    @Test fun `ANCHORS_DISCONNECTED when anchors are in separate filled components`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))  // no chain between them
        val v = validate(data, b)
        assertTrue(v.errors.any { it.type == ValidationErrorType.ANCHORS_DISCONNECTED })
        assertFalse(allAnchorsConnected(b))
        assertFalse(isComplete(data, b))
    }

    @Test fun `a locked island (no anchor) does NOT trigger ANCHORS_DISCONNECTED`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("void", false))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        setState(b, Hex(-2, 2), CellState.Placed("fire", true))  // isolated locked island, valid
        val v = validate(data, b)
        assertTrue(v.valid)  // island is allowed (spec §3.4)
    }
}
