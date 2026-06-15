package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoardSerializeTest {

    private val data = buildAspectData()

    @Test fun `round-trips a board, storing only non-EMPTY cells`() {
        val b = createBoard(3)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Placed("void", true))
        setState(b, Hex(-1, 0), CellState.Dead)
        val json = serializeBoard(b)
        assertEquals(BOARD_SCHEMA_VERSION, json.schemaVersion)
        assertEquals(3, json.radius)
        assertEquals(3, json.cells.size)
        // Round-trip via map form
        val mapForm = mapOf(
            "schemaVersion" to json.schemaVersion,
            "radius" to json.radius,
            "cells" to json.cells.map { c ->
                val m = mutableMapOf<String, Any?>("coord" to c.coord, "state" to c.state)
                if (c.aspect != null) m["aspect"] = c.aspect
                if (c.locked != null) m["locked"] = c.locked
                m
            }
        )
        val b2 = deserializeBoard(data, mapForm)
        assertEquals(CellState.Placed("void", true), getState(b2, Hex(1, 0)))
        assertEquals(CellState.Dead, getState(b2, Hex(-1, 0)))
    }

    @Test fun `rejects an unknown aspect id with a clear error`() {
        val bad = mapOf(
            "schemaVersion" to BOARD_SCHEMA_VERSION,
            "radius" to 2,
            "cells" to listOf(mapOf("coord" to "0,0", "state" to "ANCHOR", "aspect" to "nope"))
        )
        val ex = assertThrows(IllegalArgumentException::class.java) { deserializeBoard(data, bad) }
        assertTrue(ex.message?.contains("nope") == true)
    }

    @Test fun `rejects out-of-radius coords`() {
        val bad = mapOf(
            "schemaVersion" to BOARD_SCHEMA_VERSION,
            "radius" to 2,
            "cells" to listOf(mapOf("coord" to "9,9", "state" to "DEAD"))
        )
        assertThrows(IllegalArgumentException::class.java) { deserializeBoard(data, bad) }
    }

    @Test fun `rejects malformed input without crashing`() {
        assertThrows(IllegalArgumentException::class.java) { deserializeBoard(data, null) }
        assertThrows(IllegalArgumentException::class.java) {
            deserializeBoard(data, mapOf("radius" to 2))
        }
    }

    @Test fun `rejects bad schemaVersion`() {
        val bad = mapOf(
            "schemaVersion" to 999,
            "radius" to 2,
            "cells" to emptyList<Any>()
        )
        assertThrows(IllegalArgumentException::class.java) { deserializeBoard(data, bad) }
    }
}
