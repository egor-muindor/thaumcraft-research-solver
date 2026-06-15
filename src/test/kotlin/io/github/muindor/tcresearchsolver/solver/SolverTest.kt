package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SolverTest {

    private val data = buildAspectData()
    private val rich = makeInventory(
        data.universe.map { a -> a to 100 },
        DEFAULT_THRESHOLD,
    )
    private val budget = SolveBudget(maxNodes = 2_000_000, maxTimeMs = 20_000)

    private fun twoAnchorBoard(): Board {
        val b = createBoard(2)
        setState(b, Hex(-1, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("entropy"))
        return b
    }

    @Test
    fun `always returns a valid connected board when it returns one`() {
        val r = solve(SolveOptions(data, twoAnchorBoard(), rich, budget, now = { 0L }))
        assertTrue(r.status == SolverStatus.OPTIMAL || r.status == SolverStatus.FEASIBLE_TIMEOUT)
        assertNotNull(r.board)
        assertTrue(validate(data, r.board!!).valid)
    }

    @Test
    fun `finds the 1-cell optimum that leaves the lexicographically-first frontier cell EMPTY (completeness)`() {
        // air(0,0) -- void(1,0) -- entropy(2,0): the unique 1-cell bridge.
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        val r = solve(SolveOptions(data, b, rich, budget, now = { 0L }))
        assertEquals(SolverStatus.OPTIMAL, r.status)
        assertEquals(1.0, r.cost?.cells)
        assertEquals(CellState.Placed("void", false), r.board?.let { getState(it, Hex(1, 0)) })
        assertEquals(CellState.Empty, r.board?.let { getState(it, Hex(-1, 0)) })
    }

    @Test
    fun `avoids dead hexes entirely`() {
        val b = twoAnchorBoard()
        setState(b, Hex(0, 0), CellState.Dead) // force routing around center
        val r = solve(SolveOptions(data, b, rich, budget, now = { 0L }))
        if (r.board != null) {
            assertEquals(CellState.Dead, getState(r.board!!, Hex(0, 0)))
            assertTrue(validate(data, r.board!!).valid)
        }
    }

    @Test
    fun `handles a multi-anchor (3) instance`() {
        val b = createBoard(3)
        setState(b, Hex(-2, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        setState(b, Hex(0, 2), CellState.Anchor("fire"))
        val r = solve(SolveOptions(data, b, rich, budget, now = { 0L }))
        if (r.board != null) assertTrue(validate(data, r.board!!).valid)
    }

    @Test
    fun `trivially solved with 0 or 1 anchor`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        val r = solve(SolveOptions(data, b, rich, budget, now = { 0L }))
        assertEquals(SolverStatus.OPTIMAL, r.status)
    }

    @Test
    fun `UNKNOWN_TIMEOUT when truncated before any incumbent`() {
        val r = solve(SolveOptions(data, twoAnchorBoard(), rich,
            SolveBudget(maxNodes = 1, maxTimeMs = 1), now = { 0L }))
        assertTrue(r.status == SolverStatus.UNKNOWN_TIMEOUT || r.status == SolverStatus.FEASIBLE_TIMEOUT)
    }

    @Test
    fun `prefers abundant aspects - chooses a feasible board over a cheaper-by-links infeasible one`() {
        val inv = makeInventory(
            data.universe.map { a -> a to (if (a == "void") 0 else 100) },
            DEFAULT_THRESHOLD,
        )
        val r = solve(SolveOptions(data, twoAnchorBoard(), inv, budget, now = { 0L }))
        // void has zero supply but can be crafted from air+entropy (both abundant) => feasible
        assertTrue(r.status == SolverStatus.OPTIMAL || r.status == SolverStatus.FEASIBLE_TIMEOUT)
        if (r.board != null) assertTrue(validate(data, r.board!!).valid)
    }

    @Test
    fun `INFEASIBLE_INVENTORY vs UNSAT_PROVEN are distinguished on a tiny exhaustible instance`() {
        // Adjacent anchors air & earth: no valid link between them and no free cell in between
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("earth")) // adjacent + invalid link => unfixable
        val r = solve(SolveOptions(data, b, rich, budget, now = { 0L }))
        assertTrue(r.status == SolverStatus.UNSAT_PROVEN || r.status == SolverStatus.INVALID_INPUT)
    }

    @Test
    fun `allocator budget exhaustion blocks the proof on an exhaustible instance (UNKNOWN_TIMEOUT not INFEASIBLE_INVENTORY)`() {
        // Tiny exhaustible instance: only cell (1,0) is free; unique bridge is void.
        // With allocBudget(1) allocate returns UNKNOWN => anyUnknownCompetitive blocks proof.
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        for (h in boardCells(2)) {
            val k = hexKey(h)
            if (k == "0,0" || k == "2,0" || k == "1,0") continue
            setState(b, h, CellState.Dead)
        }
        val r = solve(SolveOptions(
            data, b, rich, budget,
            allocBudget = AllocBudget(maxNodes = 1),
            now = { 0L },
        ))
        assertNotEquals(SolverStatus.OPTIMAL, r.status)
        assertNotEquals(SolverStatus.INFEASIBLE_INVENTORY, r.status)
        assertEquals(SolverStatus.UNKNOWN_TIMEOUT, r.status)
    }

    @Test
    fun `beam mode explores its retained candidates and still finds a valid connected board`() {
        // Only cells 0,0 / 2,0 / 1,0 / 0,1 remain; void bridges 0,0->2,0;
        // beam:2 truncates fan-out at (0,1) but must not abort DFS early.
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(2, 0), CellState.Anchor("entropy"))
        for (h in boardCells(2)) {
            val k = hexKey(h)
            if (k == "0,0" || k == "2,0" || k == "1,0" || k == "0,1") continue
            setState(b, h, CellState.Dead)
        }
        val r = solve(SolveOptions(data, b, rich,
            SolveBudget(maxNodes = 100_000, maxTimeMs = 5_000, beam = 2), now = { 0L }))
        assertNotNull(r.board)
        assertTrue(validate(data, r.board!!).valid)
    }
}
