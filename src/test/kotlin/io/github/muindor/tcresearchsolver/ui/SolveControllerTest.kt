package io.github.muindor.tcresearchsolver.ui

import io.github.muindor.tcresearchsolver.integration.RegistryEntry
import io.github.muindor.tcresearchsolver.integration.buildAspectDataFrom
import io.github.muindor.tcresearchsolver.solver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

class FakeWorker : SolveWorkerPort {
    var started = false
    var cancelled = false
    var lastSnapshot: SolveSnapshot? = null
    var progressCallback: ((Progress) -> Unit)? = null
    var doneCallback: ((SolveResult) -> Unit)? = null

    override fun start(
        snapshot: SolveSnapshot,
        onProgress: (Progress) -> Unit,
        onDone: (SolveResult) -> Unit,
    ) {
        started = true
        lastSnapshot = snapshot
        progressCallback = onProgress
        doneCallback = onDone
    }

    override fun cancel() {
        cancelled = true
    }

    fun fireProgress(p: Progress) = progressCallback!!.invoke(p)
    fun fireDone(r: SolveResult) = doneCallback!!.invoke(r)
}

class FakeApplier : ApplierPort {
    var applyCallCount = 0
    var lastResult: SolveResult? = null
    var lastSnapshot: SolveSnapshot? = null
    var doneCallback: ((ApplyReport) -> Unit)? = null

    override fun apply(result: SolveResult, snapshot: SolveSnapshot, onDone: (ApplyReport) -> Unit) {
        applyCallCount++
        lastResult = result
        lastSnapshot = snapshot
        doneCallback = onDone
    }

    fun fireDone(report: ApplyReport) = doneCallback!!.invoke(report)
}

// ---------------------------------------------------------------------------
// Test fixture helpers
// ---------------------------------------------------------------------------

private fun makeAspectData(): AspectData {
    // Minimal aspect graph: two primals + one compound
    val entries = listOf(
        RegistryEntry("aer", emptyList(), isPrimal = true),
        RegistryEntry("ignis", emptyList(), isPrimal = true),
        RegistryEntry("lux", listOf("aer", "ignis"), isPrimal = false),
    )
    return buildAspectDataFrom(entries)
}

/**
 * Solvable board: radius=2, two anchors + one empty cell that needs to be filled.
 * Anchors at (-1,0)="aer" and (1,0)="ignis", empty cell at (0,0).
 * The aspects "aer" and "ignis" are adjacent in the graph via "lux", so the solver
 * can place "lux" at (0,0) to connect them.
 */
private fun makeSolvableSnapshot(): SolveSnapshot {
    val data = makeAspectData()
    val board = createBoard(2)
    setState(board, Hex(-1, 0), CellState.Anchor("aer"))
    setState(board, Hex(1, 0), CellState.Anchor("ignis"))
    // (0,0) is empty by default — the cell that needs filling
    val inventory = Inventory(emptyMap())
    val budget = budgetForRadius(2)
    return SolveSnapshot(
        data = data,
        board = board,
        inventory = inventory,
        budget = budget,
        pool = emptyMap(),
    )
}

/** Board with anchors but NO empty cells — all non-anchor positions are dead or locked. */
private fun makeFullyFilledSnapshot(): SolveSnapshot {
    val data = makeAspectData()
    val board = createBoard(2)
    // Two anchors
    setState(board, Hex(-1, 0), CellState.Anchor("aer"))
    setState(board, Hex(1, 0), CellState.Anchor("ignis"))
    // Fill every other cell with Dead so no Empty cells remain
    for (h in boardCells(2)) {
        val s = getState(board, h)
        if (s is CellState.Empty) {
            setState(board, h, CellState.Dead)
        }
    }
    return SolveSnapshot(data, board, Inventory(emptyMap()), budgetForRadius(2), emptyMap())
}

/** Board with one empty cell but NO anchors — not solvable. */
private fun makeNoAnchorSnapshot(): SolveSnapshot {
    val data = makeAspectData()
    val board = createBoard(2)
    // Leave (0,0) empty but place no anchors
    return SolveSnapshot(data, board, Inventory(emptyMap()), budgetForRadius(2), emptyMap())
}

/** A fake SolveResult for OPTIMAL status. */
private fun makeOptimalResult(): SolveResult =
    SolveResult(status = SolverStatus.OPTIMAL, nodes = 1, timeMs = 10L)

private fun makeProgress(nodes: Int = 5, timeMs: Long = 100L): Progress =
    Progress(nodes = nodes, best = null, timeMs = timeMs, status = "searching")

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class SolveControllerTest {

    private lateinit var worker: FakeWorker
    private lateinit var applier: FakeApplier

    @BeforeEach
    fun setup() {
        worker = FakeWorker()
        applier = FakeApplier()
    }

    private fun controller(previewConfirm: Boolean = true) =
        SolveController(worker, applier, previewConfirm)

    // ------------------------------------------------------------------
    // 1. start() from Idle with solvable snapshot → Solving; worker.start called
    // ------------------------------------------------------------------
    @Test
    fun `start from Idle with solvable snapshot transitions to Solving and calls worker start`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        assertTrue(ctrl.state is SolveState.Solving, "expected Solving, got ${ctrl.state}")
        assertTrue(worker.started, "expected worker.start to be called")
        assertNotNull(worker.lastSnapshot)
    }

    @Test
    fun `initial state is Idle`() {
        val ctrl = controller()
        assertTrue(ctrl.state is SolveState.Idle)
    }

    // ------------------------------------------------------------------
    // 2. start() no-op when snapshot null, no empty cells, or no anchors
    // ------------------------------------------------------------------
    @Test
    fun `start no-op when snapshot is null`() {
        val ctrl = controller()
        ctrl.start()
        assertTrue(ctrl.state is SolveState.Idle, "should stay Idle")
        assertFalse(worker.started)
    }

    @Test
    fun `start no-op when board has no empty cells`() {
        val ctrl = controller()
        ctrl.snapshot = makeFullyFilledSnapshot()
        ctrl.start()
        assertTrue(ctrl.state is SolveState.Idle, "should stay Idle — no empty cells")
        assertFalse(worker.started)
    }

    @Test
    fun `start no-op when board has no anchors`() {
        val ctrl = controller()
        ctrl.snapshot = makeNoAnchorSnapshot()
        ctrl.start()
        assertTrue(ctrl.state is SolveState.Idle, "should stay Idle — no anchors")
        assertFalse(worker.started)
    }

    // ------------------------------------------------------------------
    // 3. start() ignored when not Idle
    // ------------------------------------------------------------------
    @Test
    fun `start ignored when already Solving`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start() // → Solving

        val workerStartedCount = if (worker.started) 1 else 0
        worker.started = false // reset to detect a second call
        ctrl.start() // should be ignored
        assertFalse(worker.started, "start() should be ignored when already Solving")
        assertTrue(ctrl.state is SolveState.Solving)
    }

    // ------------------------------------------------------------------
    // 4. onProgress updates Solving payload; ignored when not Solving
    // ------------------------------------------------------------------
    @Test
    fun `onProgress updates Solving state payload`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        val p = makeProgress(nodes = 42, timeMs = 200L)
        worker.fireProgress(p)

        val state = ctrl.state
        assertTrue(state is SolveState.Solving)
        assertEquals(42, (state as SolveState.Solving).progress?.nodes)
    }

    @Test
    fun `onProgress ignored when state is not Solving`() {
        val ctrl = controller()
        // State is Idle — onProgress should do nothing
        ctrl.onProgress(makeProgress())
        assertTrue(ctrl.state is SolveState.Idle)
    }

    // ------------------------------------------------------------------
    // 5. onSolved(OPTIMAL) with previewConfirm=true → Preview
    // ------------------------------------------------------------------
    @Test
    fun `onSolved OPTIMAL with previewConfirm true transitions to Preview`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        val result = makeOptimalResult()
        worker.fireDone(result)

        val state = ctrl.state
        assertTrue(state is SolveState.Preview, "expected Preview, got $state")
        assertSame(result, (state as SolveState.Preview).result)
        assertEquals(0, applier.applyCallCount, "applier should NOT be called yet")
    }

    // ------------------------------------------------------------------
    // 6. onSolved(OPTIMAL) with previewConfirm=false → Applying + applier.apply
    // ------------------------------------------------------------------
    @Test
    fun `onSolved OPTIMAL with previewConfirm false auto-applies and transitions to Applying`() {
        val ctrl = controller(previewConfirm = false)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        val result = makeOptimalResult()
        worker.fireDone(result)

        assertTrue(ctrl.state is SolveState.Applying, "expected Applying, got ${ctrl.state}")
        assertEquals(1, applier.applyCallCount, "applier.apply should be called once")
    }

    @Test
    fun `onSolved FEASIBLE_TIMEOUT with previewConfirm true transitions to Preview`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        worker.fireDone(SolveResult(status = SolverStatus.FEASIBLE_TIMEOUT, nodes = 1, timeMs = 5000L))

        assertTrue(ctrl.state is SolveState.Preview)
    }

    // ------------------------------------------------------------------
    // 7. onSolved with failure statuses → Error
    // ------------------------------------------------------------------
    @Test
    fun `onSolved INFEASIBLE_INVENTORY transitions to Error with message`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        worker.fireDone(SolveResult(status = SolverStatus.INFEASIBLE_INVENTORY, nodes = 1, timeMs = 10L))

        val state = ctrl.state
        assertTrue(state is SolveState.Error, "expected Error, got $state")
        assertTrue((state as SolveState.Error).message.isNotEmpty())
    }

    @Test
    fun `onSolved UNSAT_PROVEN transitions to Error`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        worker.fireDone(SolveResult(status = SolverStatus.UNSAT_PROVEN, nodes = 1, timeMs = 10L))

        assertTrue(ctrl.state is SolveState.Error)
    }

    @Test
    fun `onSolved UNKNOWN_TIMEOUT transitions to Error`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        worker.fireDone(SolveResult(status = SolverStatus.UNKNOWN_TIMEOUT, nodes = 1, timeMs = 10L))

        assertTrue(ctrl.state is SolveState.Error)
    }

    @Test
    fun `onSolved uses result message when present`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()

        worker.fireDone(
            SolveResult(
                status = SolverStatus.INFEASIBLE_INVENTORY,
                message = "custom error detail",
                nodes = 1,
                timeMs = 10L,
            )
        )

        val state = ctrl.state as SolveState.Error
        assertTrue(state.message.contains("custom error detail"), "message should contain result.message")
    }

    // ------------------------------------------------------------------
    // 8. cancel() from Solving → Idle + worker.cancel called; late onSolved(CANCELLED) ignored
    // ------------------------------------------------------------------
    @Test
    fun `cancel from Solving transitions to Idle and calls worker cancel`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        assertTrue(ctrl.state is SolveState.Solving)

        ctrl.cancel()

        assertTrue(ctrl.state is SolveState.Idle, "expected Idle after cancel, got ${ctrl.state}")
        assertTrue(worker.cancelled)
    }

    @Test
    fun `late onSolved CANCELLED after cancel is ignored`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        ctrl.cancel() // → Idle

        // Simulate stale callback arriving from the background thread
        worker.fireDone(SolveResult(status = SolverStatus.CANCELLED, nodes = 0, timeMs = 0L))

        // Should still be Idle, not transition to something else
        assertTrue(ctrl.state is SolveState.Idle, "stale CANCELLED callback should be ignored")
    }

    @Test
    fun `cancel from non-Solving state is no-op`() {
        val ctrl = controller()
        ctrl.cancel() // from Idle — should be no-op
        assertTrue(ctrl.state is SolveState.Idle)
        assertFalse(worker.cancelled)
    }

    // ------------------------------------------------------------------
    // 9. apply() from Preview → Applying; onApplied empty→Done; onApplied rejected→Error
    // ------------------------------------------------------------------
    @Test
    fun `apply from Preview transitions to Applying and calls applier`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        assertTrue(ctrl.state is SolveState.Preview)

        ctrl.apply()

        assertTrue(ctrl.state is SolveState.Applying, "expected Applying, got ${ctrl.state}")
        assertEquals(1, applier.applyCallCount)
    }

    @Test
    fun `onApplied with empty rejected cells transitions to Done`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        ctrl.apply()

        applier.fireDone(ApplyReport(rejectedCells = emptySet()))

        assertTrue(ctrl.state is SolveState.Done, "expected Done, got ${ctrl.state}")
    }

    @Test
    fun `onApplied with rejected cells transitions to Error with cell info`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        ctrl.apply()

        applier.fireDone(ApplyReport(rejectedCells = setOf("0,0", "1,0")))

        val state = ctrl.state
        assertTrue(state is SolveState.Error, "expected Error, got $state")
        val msg = (state as SolveState.Error).message
        assertTrue(msg.contains("2"), "error message should mention count: $msg")
    }

    @Test
    fun `apply no-op when not in Preview state`() {
        val ctrl = controller()
        ctrl.apply() // from Idle — should be no-op
        assertEquals(0, applier.applyCallCount)
        assertTrue(ctrl.state is SolveState.Idle)
    }

    // ------------------------------------------------------------------
    // 10. reset() from any state → Idle; cancels worker when Solving
    // ------------------------------------------------------------------
    @Test
    fun `reset from Solving cancels worker and transitions to Idle`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        ctrl.reset()

        assertTrue(ctrl.state is SolveState.Idle)
        assertTrue(worker.cancelled)
    }

    @Test
    fun `reset from Preview transitions to Idle`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        assertTrue(ctrl.state is SolveState.Preview)

        ctrl.reset()

        assertTrue(ctrl.state is SolveState.Idle)
    }

    @Test
    fun `reset from Done transitions to Idle`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        ctrl.apply()
        applier.fireDone(ApplyReport(emptySet()))
        assertTrue(ctrl.state is SolveState.Done)

        ctrl.reset()

        assertTrue(ctrl.state is SolveState.Idle)
    }

    @Test
    fun `reset from Error transitions to Idle`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(SolveResult(SolverStatus.UNSAT_PROVEN, nodes = 1, timeMs = 10L))
        assertTrue(ctrl.state is SolveState.Error)

        ctrl.reset()

        assertTrue(ctrl.state is SolveState.Idle)
    }

    @Test
    fun `reset from Idle is a no-op`() {
        val ctrl = controller()
        ctrl.reset()
        assertTrue(ctrl.state is SolveState.Idle)
        assertFalse(worker.cancelled)
    }

    // ------------------------------------------------------------------
    // 11. buttonLabel() and onButtonClicked() dispatch correctly per state
    // ------------------------------------------------------------------
    @Test
    fun `buttonLabel is Solve when Idle`() {
        assertEquals("Solve", controller().buttonLabel())
    }

    @Test
    fun `buttonLabel is Cancel when Solving`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        assertEquals("Cancel", ctrl.buttonLabel())
    }

    @Test
    fun `buttonLabel is Apply when Preview`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        assertEquals("Apply", ctrl.buttonLabel())
    }

    @Test
    fun `buttonLabel is Applying when Applying`() {
        val ctrl = controller(previewConfirm = false)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        assertEquals("Applying…", ctrl.buttonLabel())
    }

    @Test
    fun `buttonLabel is Reset when Done`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        ctrl.apply()
        applier.fireDone(ApplyReport(emptySet()))
        assertEquals("Reset", ctrl.buttonLabel())
    }

    @Test
    fun `buttonLabel is Reset when Error`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(SolveResult(SolverStatus.UNSAT_PROVEN, nodes = 1, timeMs = 10L))
        assertEquals("Reset", ctrl.buttonLabel())
    }

    @Test
    fun `onButtonClicked from Idle calls start`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.onButtonClicked()
        assertTrue(ctrl.state is SolveState.Solving)
    }

    @Test
    fun `onButtonClicked from Solving calls cancel`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        ctrl.onButtonClicked()
        assertTrue(ctrl.state is SolveState.Idle)
        assertTrue(worker.cancelled)
    }

    @Test
    fun `onButtonClicked from Preview calls apply`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        ctrl.onButtonClicked()
        assertTrue(ctrl.state is SolveState.Applying)
    }

    @Test
    fun `onButtonClicked from Done calls reset`() {
        val ctrl = controller(previewConfirm = true)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        ctrl.apply()
        applier.fireDone(ApplyReport(emptySet()))
        ctrl.onButtonClicked()
        assertTrue(ctrl.state is SolveState.Idle)
    }

    @Test
    fun `onButtonClicked from Error calls reset`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(SolveResult(SolverStatus.UNSAT_PROVEN, nodes = 1, timeMs = 10L))
        ctrl.onButtonClicked()
        assertTrue(ctrl.state is SolveState.Idle)
    }

    @Test
    fun `onButtonClicked from Applying is no-op`() {
        val ctrl = controller(previewConfirm = false)
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireDone(makeOptimalResult())
        assertTrue(ctrl.state is SolveState.Applying)
        ctrl.onButtonClicked() // should be no-op
        assertTrue(ctrl.state is SolveState.Applying)
    }

    // ------------------------------------------------------------------
    // progressText()
    // ------------------------------------------------------------------
    @Test
    fun `progressText returns null when not Solving`() {
        assertNull(controller().progressText())
    }

    @Test
    fun `progressText returns null when Solving with no progress yet`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        assertNull(ctrl.progressText())
    }

    @Test
    fun `progressText returns formatted text when Solving with progress`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireProgress(Progress(nodes = 1234, best = null, timeMs = 3500L, status = "searching"))
        val text = ctrl.progressText()
        assertNotNull(text)
        assertTrue(text!!.contains("3"), "should contain seconds: $text")
        assertTrue(text.contains("1234"), "should contain nodes: $text")
    }

    @Test
    fun `progressText includes best cost when present`() {
        val ctrl = controller()
        ctrl.snapshot = makeSolvableSnapshot()
        ctrl.start()
        worker.fireProgress(Progress(nodes = 10, best = Cost(1.0, 3.0), timeMs = 1000L, status = "searching"))
        val text = ctrl.progressText()
        assertNotNull(text)
        assertTrue(text!!.contains("3"), "should contain cells cost: $text")
    }
}
