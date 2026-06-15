package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HexTest {
    @Test fun `round-trips a coord`() {
        assertEquals(Hex(-2, 3), parseHexKey(hexKey(Hex(-2, 3))))
        assertEquals("0,0", hexKey(Hex(0, 0)))
    }
    @Test fun `6 unit directions summing to zero`() {
        assertEquals(6, HEX_DIRECTIONS.size)
        assertEquals(Hex(0, 0), HEX_DIRECTIONS.fold(Hex(0, 0)) { a, d -> Hex(a.q + d.q, a.r + d.r) })
    }
    @Test fun `6 distinct neighbors at distance 1`() {
        val n = neighborsOf(Hex(0, 0))
        assertEquals(6, n.size)
        n.forEach { assertEquals(1, distance(Hex(0, 0), it)) }
        assertEquals(6, n.map { hexKey(it) }.toSet().size)
    }
    @Test fun `cube distance`() {
        assertEquals(0, distance(Hex(0, 0), Hex(0, 0)))
        assertEquals(2, distance(Hex(0, 0), Hex(2, -1)))
        assertEquals(4, distance(Hex(-1, -1), Hex(1, 1)))
    }
    @Test fun `cells per radius 1+3R(R+1)`() {
        assertEquals(19, boardCells(2).size)
        assertEquals(37, boardCells(3).size)
        assertEquals(61, boardCells(4).size)
        assertEquals(91, boardCells(5).size)
    }
    @Test fun `isOnBoard agrees with distance`() {
        assertTrue(isOnBoard(Hex(2, 0), 2))
        assertFalse(isOnBoard(Hex(3, 0), 2))
        assertTrue(boardCells(3).all { isOnBoard(it, 3) })
    }
    @Test fun `rejects malformed hex keys`() {
        for (bad in listOf("1", ",1", "1,", "1,2,3", "", "a,b", "1.5,2", " ,3"))
            assertThrows(IllegalArgumentException::class.java) { parseHexKey(bad) }
    }
}
