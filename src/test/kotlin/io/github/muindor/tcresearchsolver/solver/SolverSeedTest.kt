package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SolverSeedTest {

    private val data = buildAspectData()
    private val rich = makeInventory(data.universe.map { a -> a to 100 }, DEFAULT_THRESHOLD)

    @Test
    fun `produces the same OPTIMAL cost with seeding enabled as the exhaustive search`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        val r = solve(SolveOptions(
            data, b, rich,
            SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000),
            seed = true,
            now = { 0L },
        ))
        assertEquals(SolverStatus.OPTIMAL, r.status)
        assertEquals(1.0, r.cost?.cells) // still the 1-cell optimum
        assertTrue(r.board != null && validate(data, r.board!!).valid)
    }

    @Test
    fun `seed yields a valid connected incumbent on a hard R5 4-anchor board (anytime)`() {
        val b = createBoard(5)
        setState(b, Hex(-4, 0), CellState.Anchor("air"))
        setState(b, Hex(4, 0), CellState.Anchor("entropy"))
        setState(b, Hex(0, 4), CellState.Anchor("fire"))
        setState(b, Hex(0, -4), CellState.Anchor("water"))
        val r = solve(SolveOptions(
            data, b, rich,
            SolveBudget(maxNodes = 1, maxTimeMs = 3000),
            seed = true,
            now = { 0L },
        ))
        assertNotNull(r.board)
        assertTrue(validate(data, r.board!!).valid)
    }

    @Test
    fun `seed yields a valid connected incumbent on a hard R4 4-anchor board (anytime)`() {
        val b = createBoard(4)
        setState(b, Hex(-3, 0), CellState.Anchor("air"))
        setState(b, Hex(3, 0), CellState.Anchor("entropy"))
        setState(b, Hex(0, 3), CellState.Anchor("fire"))
        setState(b, Hex(0, -3), CellState.Anchor("water"))
        val r = solve(SolveOptions(
            data, b, rich,
            SolveBudget(maxNodes = 1, maxTimeMs = 3000),
            seed = true,
            now = { 0L },
        ))
        assertNotNull(r.board)
        assertTrue(validate(data, r.board!!).valid)
    }

    @Test
    fun `seed does not degrade the proven optimum on R2 (explicit regression)`() {
        val b1 = createBoard(2)
        setState(b1, Hex(0, 0), CellState.Anchor("air"))
        setState(b1, Hex(2, 0), CellState.Anchor("entropy"))
        val withoutSeed = solve(SolveOptions(
            data, b1, rich,
            SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000),
            seed = false,
            now = { 0L },
        ))

        val b2 = createBoard(2)
        setState(b2, Hex(0, 0), CellState.Anchor("air"))
        setState(b2, Hex(2, 0), CellState.Anchor("entropy"))
        val withSeed = solve(SolveOptions(
            data, b2, rich,
            SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000),
            seed = true,
            now = { 0L },
        ))

        assertEquals(SolverStatus.OPTIMAL, withoutSeed.status)
        assertEquals(SolverStatus.OPTIMAL, withSeed.status)
        assertEquals(withoutSeed.cost?.cells, withSeed.cost?.cells)
    }
}
