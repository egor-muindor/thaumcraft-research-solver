package io.github.muindor.tcresearchsolver.solver

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SolverPrevalidateTest {

    private val data = buildAspectData()
    private val inv = makeInventory(data.universe.map { a -> a to 100 }, DEFAULT_THRESHOLD)

    @Test
    fun `returns INVALID_INPUT when starting anchors are adjacent but unlinkable`() {
        val b = createBoard(2)
        setState(b, Hex(0, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("earth")) // adjacent + invalid link, unfixable
        val r = solveWithValidation(SolveOptions(
            data, b, inv,
            SolveBudget(maxNodes = 1000, maxTimeMs = 100),
            now = { 0L },
        ))
        assertEquals(SolverStatus.INVALID_INPUT, r.status)
        assertTrue(r.errors?.any { e ->
            e.type == ValidationErrorType.INVALID_LINK || e.type == ValidationErrorType.SAME_ASPECT_ADJACENT
        } == true)
    }

    @Test
    fun `does not reject a solvable start (disconnected anchors with room to route)`() {
        val b = createBoard(2)
        setState(b, Hex(-1, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("entropy"))
        val r = solveWithValidation(SolveOptions(
            data, b, inv,
            SolveBudget(maxNodes = 1_000_000, maxTimeMs = 5000),
            now = { 0L },
        ))
        assertNotEquals(SolverStatus.INVALID_INPUT, r.status)
    }

    @Test
    fun `returns INVALID_INPUT on negative supply (spec §4_1)`() {
        val b = createBoard(2)
        setState(b, Hex(-1, 0), CellState.Anchor("air"))
        setState(b, Hex(1, 0), CellState.Anchor("entropy"))
        val bad = makeInventory(listOf("air" to -3), DEFAULT_THRESHOLD)
        val r = solveWithValidation(SolveOptions(
            data, b, bad,
            SolveBudget(maxNodes = 1000, maxTimeMs = 100),
            now = { 0L },
        ))
        assertEquals(SolverStatus.INVALID_INPUT, r.status)
    }

    @Test
    fun `returns INVALID_INPUT when more than 8 anchors are placed (spec §5_1)`() {
        val b = createBoard(5)
        val cells = listOf(
            Hex(0,0), Hex(3,0), Hex(-3,0), Hex(0,3), Hex(0,-3),
            Hex(3,-3), Hex(-3,3), Hex(5,0), Hex(-5,0),
        )
        val aspects = listOf("air","earth","fire","water","order","entropy","void","light","energy")
        cells.forEachIndexed { i, c ->
            setState(b, c, CellState.Anchor(aspects[i]))
        }
        val r = solveWithValidation(SolveOptions(
            data, b, inv,
            SolveBudget(maxNodes = 1000, maxTimeMs = 100),
            now = { 0L },
        ))
        assertEquals(SolverStatus.INVALID_INPUT, r.status)
    }
}
