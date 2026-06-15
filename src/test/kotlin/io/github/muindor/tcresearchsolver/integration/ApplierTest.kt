package io.github.muindor.tcresearchsolver.integration

import io.github.muindor.tcresearchsolver.solver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [planApply] — the pure planner in Applier.kt.
 *
 * IMPORTANT: These tests NEVER call [Applier.apply] or [Applier.postVerify] — those touch
 * TC / RT classes not available in unit-test JVM. Only the pure [planApply] function is tested.
 */
class ApplierTest {

    // ----- helpers -----

    /**
     * Build a minimal AspectData with:
     *   primals: air, entropy
     *   compounds: void = air + entropy
     * Uses buildAspectDataFrom (TC-free) from AspectDataProvider.kt.
     */
    private fun minimalData(): io.github.muindor.tcresearchsolver.solver.AspectData =
        buildAspectDataFrom(
            listOf(
                RegistryEntry("air",     emptyList(), isPrimal = true),
                RegistryEntry("entropy", emptyList(), isPrimal = true),
                RegistryEntry("void",    listOf("air", "entropy"), isPrimal = false),
            )
        )

    /**
     * Build a 3-level AspectData:
     *   primals: air, entropy
     *   void    = air + entropy
     *   compound = void + air   (3-level: compound needs void which needs air+entropy)
     */
    private fun deepData(): io.github.muindor.tcresearchsolver.solver.AspectData =
        buildAspectDataFrom(
            listOf(
                RegistryEntry("air",      emptyList(), isPrimal = true),
                RegistryEntry("entropy",  emptyList(), isPrimal = true),
                RegistryEntry("void",     listOf("air", "entropy"), isPrimal = false),
                RegistryEntry("compound", listOf("void", "air"),    isPrimal = false),
            )
        )

    /**
     * Build an R2 board with the given states set at their hexes.
     * [setups] is a list of (Hex, CellState) pairs to set.
     */
    private fun buildBoard(vararg setups: Pair<Hex, CellState>): Board {
        val board = createBoard(2)
        for ((h, s) in setups) setState(board, h, s)
        return board
    }

    private fun solveResult(board: Board) =
        SolveResult(SolverStatus.OPTIMAL, board = board)

    // ----- Test 1: Plan-order (the spec test) -----

    @Test fun `pool has no void but abundant air+entropy - plan emits Combine before Place`() {
        val data = minimalData()
        val board = buildBoard(
            Hex(0, 0) to CellState.Placed("void", locked = false),
        )
        val pool = mapOf("air" to 10, "entropy" to 10)

        val ops = planApply(solveResult(board), data, pool)

        // Must have exactly: Combine(air, entropy), Place("0,0", void)
        assertEquals(2, ops.size, "Expected 2 ops but got: $ops")
        val combine = ops[0]
        val place   = ops[1]
        assertTrue(combine is ApplyOp.Combine, "ops[0] should be Combine, got $combine")
        assertTrue(place   is ApplyOp.Place,   "ops[1] should be Place, got $place")
        combine as ApplyOp.Combine
        place   as ApplyOp.Place
        // recipe is void = air + entropy
        assertEquals(setOf("air", "entropy"), setOf(combine.a, combine.b))
        assertEquals("void", place.aspect)
        assertEquals("0,0",  place.key)

        // Combine index < Place index (already guaranteed by ordering above, but assert explicitly)
        assertTrue(ops.indexOf(combine) < ops.indexOf(place))
    }

    // ----- Test 2: Direct draw, no combine -----

    @Test fun `pool has enough void - plan emits only Place, no Combine`() {
        val data = minimalData()
        val board = buildBoard(
            Hex(0, 0) to CellState.Placed("void", locked = false),
        )
        val pool = mapOf("void" to 5)

        val ops = planApply(solveResult(board), data, pool)

        assertEquals(1, ops.size, "Expected 1 op but got: $ops")
        val op = ops[0]
        assertTrue(op is ApplyOp.Place, "Expected Place but got $op")
        op as ApplyOp.Place
        assertEquals("void", op.aspect)
        assertEquals("0,0",  op.key)
    }

    // ----- Test 3: Locked cells and Anchors are ignored -----

    @Test fun `locked Placed and Anchor cells are not turned into Place ops`() {
        val data = minimalData()
        val board = buildBoard(
            Hex(0,  0) to CellState.Placed("void", locked = true),   // note cell — ignore
            Hex(1,  0) to CellState.Anchor("air"),                    // anchor    — ignore
            Hex(0, -1) to CellState.Placed("entropy", locked = false), // solver cell — include
        )
        val pool = mapOf("entropy" to 3)

        val ops = planApply(solveResult(board), data, pool)

        // Only "entropy" at "0,-1" should produce a Place op
        val places = ops.filterIsInstance<ApplyOp.Place>()
        assertEquals(1, places.size, "Expected 1 Place but got: $places")
        assertEquals("0,-1",   places[0].key)
        assertEquals("entropy", places[0].aspect)

        // No Combine ops (entropy is primal, drawn directly)
        val combines = ops.filterIsInstance<ApplyOp.Combine>()
        assertTrue(combines.isEmpty(), "Expected no Combine ops but got: $combines")
    }

    // ----- Test 4: hexKey ordering of Place ops -----

    @Test fun `two solver placements produce Place ops in ascending hexKey order`() {
        val data = minimalData()
        // Place two primals (direct draw — no Combine needed)
        val board = buildBoard(
            Hex( 1, 0) to CellState.Placed("air",     locked = false),
            Hex(-1, 0) to CellState.Placed("entropy", locked = false),
        )
        val pool = mapOf("air" to 1, "entropy" to 1)

        val ops = planApply(solveResult(board), data, pool)

        val places = ops.filterIsInstance<ApplyOp.Place>()
        assertEquals(2, places.size, "Expected 2 Place ops but got: $places")

        // hexKey("-1,0") = "-1,0", hexKey("1,0") = "1,0"; String compare: "-" < "1"
        assertEquals("-1,0", places[0].key, "First Place should be -1,0 (lexicographically smaller)")
        assertEquals("1,0",  places[1].key, "Second Place should be 1,0")
    }

    // ----- Test 5: Nested craft (deepest-first) -----

    @Test fun `3-level recipe emits deeper Combine before shallower Combine before Place`() {
        // compound = void + air; void = air + entropy
        // Pool has only primals: air x10, entropy x10
        val data = deepData()
        val board = buildBoard(
            Hex(0, 0) to CellState.Placed("compound", locked = false),
        )
        val pool = mapOf("air" to 10, "entropy" to 10)

        val ops = planApply(solveResult(board), data, pool)

        val combines = ops.filterIsInstance<ApplyOp.Combine>()
        val places   = ops.filterIsInstance<ApplyOp.Place>()

        // Expect 2 Combines and 1 Place
        assertEquals(2, combines.size, "Expected 2 Combine ops but got: $combines")
        assertEquals(1, places.size,   "Expected 1 Place op but got: $places")

        val place = places[0]
        assertEquals("compound", place.aspect)
        assertEquals("0,0",      place.key)

        // The deeper Combine (produces void from air+entropy) must precede the shallower one
        // (produces compound from void+air).
        val idxVoidCombine = ops.indexOfFirst { it is ApplyOp.Combine &&
                (it as ApplyOp.Combine).let { c -> setOf(c.a, c.b) == setOf("air", "entropy") } }
        val idxCompoundCombine = ops.indexOfFirst { it is ApplyOp.Combine &&
                (it as ApplyOp.Combine).let { c -> setOf(c.a, c.b) == setOf("void", "air") } }
        val idxPlace = ops.indexOf(place)

        assertTrue(idxVoidCombine >= 0, "Did not find Combine(air,entropy)")
        assertTrue(idxCompoundCombine >= 0, "Did not find Combine(void,air)")
        assertTrue(idxVoidCombine < idxCompoundCombine,
            "Combine(air,entropy) [idx=$idxVoidCombine] must precede Combine(void,air) [idx=$idxCompoundCombine]")
        assertTrue(idxCompoundCombine < idxPlace,
            "Combine(void,air) [idx=$idxCompoundCombine] must precede Place [idx=$idxPlace]")
    }

    // ----- Test 6: null board (no-op) -----

    @Test fun `null board in SolveResult returns empty ops list`() {
        val data = minimalData()
        val result = SolveResult(SolverStatus.UNSAT_PROVEN, board = null)
        val ops = planApply(result, data, emptyMap())
        assertTrue(ops.isEmpty(), "Expected empty list for null board but got: $ops")
    }
}
