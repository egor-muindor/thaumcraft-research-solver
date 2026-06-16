package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Precedence for [resolveBudget]: per-radius override falls back to the built-in time,
 * and the global cap is a true ceiling (min), never an "override-wins".
 */
class BudgetTest {

    private val base = SolveBudget(maxNodes = 4_000_000, maxTimeMs = 20_000, beam = 12)

    @Test
    fun `no overrides returns the built-in budget unchanged`() {
        val r = resolveBudget(base, perRadiusMs = 0, globalCapMs = 0)
        assertEquals(20_000L, r.maxTimeMs)
        assertEquals(4_000_000, r.maxNodes)
        assertEquals(12, r.beam)
    }

    @Test
    fun `per-radius override replaces the built-in time, keeping nodes and beam`() {
        val r = resolveBudget(base, perRadiusMs = 3_000, globalCapMs = 0)
        assertEquals(3_000L, r.maxTimeMs)
        assertEquals(4_000_000, r.maxNodes)
        assertEquals(12, r.beam)
    }

    @Test
    fun `global cap below the built-in time wins as a ceiling`() {
        val r = resolveBudget(base, perRadiusMs = 0, globalCapMs = 5_000)
        assertEquals(5_000L, r.maxTimeMs)
    }

    @Test
    fun `global cap above the built-in time does not raise it`() {
        val r = resolveBudget(base, perRadiusMs = 0, globalCapMs = 25_000)
        assertEquals(20_000L, r.maxTimeMs)
    }

    @Test
    fun `global cap is the min of cap and per-radius override`() {
        val capBelow = resolveBudget(base, perRadiusMs = 15_000, globalCapMs = 3_000)
        assertEquals(3_000L, capBelow.maxTimeMs) // cap < override => cap

        val capAbove = resolveBudget(base, perRadiusMs = 4_000, globalCapMs = 10_000)
        assertEquals(4_000L, capAbove.maxTimeMs) // override < cap => override
    }

    @Test
    fun `nodes and beam are always preserved across overrides`() {
        val r = resolveBudget(base, perRadiusMs = 1_000, globalCapMs = 500)
        assertEquals(500L, r.maxTimeMs)
        assertEquals(4_000_000, r.maxNodes)
        assertEquals(12, r.beam)
    }
}
