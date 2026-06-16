package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Fast-solve (`stopAtFirstFeasible`): return the first valid solution instead of proving
 * optimality. Trades quality for speed; never reports OPTIMAL.
 */
class SolverFastModeTest {

    private val data = buildAspectData()
    private val rich = makeInventory(data.universe.map { a -> a to 100 }, DEFAULT_THRESHOLD)

    private fun r2Bridge(): Board {
        // air(0,0) -- void(1,0) -- entropy(2,0): exhaustible, unique 1-cell optimum.
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        return b
    }

    private fun r4FourAnchors(): Board {
        val b = createBoard(4)
        setState(b, Hex(-3, 0), CellState.Anchor("air"))
        setState(b, Hex(3, 0), CellState.Anchor("entropy"))
        setState(b, Hex(0, 3), CellState.Anchor("fire"))
        setState(b, Hex(0, -3), CellState.Anchor("water"))
        return b
    }

    @Test
    fun `fast mode returns first feasible (FEASIBLE_TIMEOUT) where full search proves OPTIMAL`() {
        val budget = SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000)
        val full = solve(SolveOptions(
            data, r2Bridge(), rich, budget, seed = false, stopAtFirstFeasible = false, now = { 0L },
        ))
        val fast = solve(SolveOptions(
            data, r2Bridge(), rich, budget, seed = false, stopAtFirstFeasible = true, now = { 0L },
        ))

        assertEquals(SolverStatus.OPTIMAL, full.status)
        assertEquals(SolverStatus.FEASIBLE_TIMEOUT, fast.status)
        assertNotNull(fast.board)
        assertTrue(validate(data, fast.board!!).valid)
        assertTrue(fast.nodes < full.nodes, "fast stops early: ${fast.nodes} should be < ${full.nodes}")
    }

    @Test
    fun `fast mode with seed skips the DFS entirely when the seed is already feasible`() {
        val budget = SolveBudget(maxNodes = 100_000, maxTimeMs = 5_000)
        val fast = solve(SolveOptions(
            data, r4FourAnchors(), rich, budget, seed = true, stopAtFirstFeasible = true, now = { 0L },
        ))
        assertEquals(SolverStatus.FEASIBLE_TIMEOUT, fast.status)
        assertEquals(0, fast.nodes, "seed produced a feasible incumbent => DFS not entered")
        assertNotNull(fast.board)
        assertTrue(validate(data, fast.board!!).valid)
    }

    @Test
    fun `fast mode off is unchanged - still proves the OPTIMAL 1-cell optimum`() {
        val budget = SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000)
        val r = solve(SolveOptions(
            data, r2Bridge(), rich, budget, stopAtFirstFeasible = false, now = { 0L },
        ))
        assertEquals(SolverStatus.OPTIMAL, r.status)
        assertEquals(1.0, r.cost?.cells)
    }

    @Test
    fun `fast mode does not change an infeasible outcome`() {
        // Adjacent air & earth with no valid link and no cell between => unsatisfiable.
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("earth"))
        val budget = SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000)
        val nf = solve(SolveOptions(data, b, rich, budget, stopAtFirstFeasible = false, now = { 0L }))
        val f = solve(SolveOptions(data, b, rich, budget, stopAtFirstFeasible = true, now = { 0L }))
        assertEquals(nf.status, f.status)
        assertEquals(SolverStatus.UNSAT_PROVEN, f.status)
    }
}
