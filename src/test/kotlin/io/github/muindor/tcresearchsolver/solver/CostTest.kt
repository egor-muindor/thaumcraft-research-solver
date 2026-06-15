package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CostTest {

    @Test
    fun `orders by scarcity first, then cells`() {
        assertTrue(compareCost(Cost(1.0, 100.0), Cost(2.0, 0.0)) < 0)
        assertTrue(compareCost(Cost(2.0, 1.0), Cost(2.0, 3.0)) < 0)
        assertEquals(0, compareCost(Cost(2.0, 3.0), Cost(2.0, 3.0)))
    }

    @Test
    fun `adds componentwise (Infinity-safe)`() {
        assertEquals(Cost(4.0, 6.0), addCost(Cost(1.0, 2.0), Cost(3.0, 4.0)))
        assertEquals(Double.POSITIVE_INFINITY, addCost(INF_COST, ZERO_COST).scarcity)
    }

    @Test
    fun `lessThan is strict`() {
        assertTrue(lessThan(Cost(1.0, 0.0), Cost(1.0, 1.0)))
        assertFalse(lessThan(Cost(1.0, 1.0), Cost(1.0, 1.0)))
    }

    @Test
    fun `INF_COST compares greater than any finite cost`() {
        assertTrue(compareCost(INF_COST, Cost(1e9, 1e9)) > 0)
        assertTrue(lessThan(Cost(1.0, 1.0), INF_COST))
        assertEquals(0, compareCost(INF_COST, INF_COST))
    }
}
