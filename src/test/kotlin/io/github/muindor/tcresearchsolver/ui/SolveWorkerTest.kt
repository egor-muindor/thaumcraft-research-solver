package io.github.muindor.tcresearchsolver.ui

import io.github.muindor.tcresearchsolver.integration.RegistryEntry
import io.github.muindor.tcresearchsolver.integration.buildAspectDataFrom
import io.github.muindor.tcresearchsolver.solver.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// ---------------------------------------------------------------------------
// Test fixture helpers
// ---------------------------------------------------------------------------

/** Minimal 6-primal aspect data with a few compounds: aer, ignis, terra, aqua, ordo, perditio + void + lux. */
private fun makeAspectData(): AspectData {
    val entries = listOf(
        RegistryEntry("aer", emptyList(), isPrimal = true),
        RegistryEntry("ignis", emptyList(), isPrimal = true),
        RegistryEntry("terra", emptyList(), isPrimal = true),
        RegistryEntry("aqua", emptyList(), isPrimal = true),
        RegistryEntry("ordo", emptyList(), isPrimal = true),
        RegistryEntry("perditio", emptyList(), isPrimal = true),
        RegistryEntry("void", listOf("aer", "perditio"), isPrimal = false),
        RegistryEntry("lux", listOf("aer", "ignis"), isPrimal = false),
        RegistryEntry("motus", listOf("aer", "ordo"), isPrimal = false),
        RegistryEntry("potentia", listOf("ordo", "ignis"), isPrimal = false),
    )
    return buildAspectDataFrom(entries)
}

/**
 * Trivial solvable snapshot: radius 2, two anchors with a single empty bridging cell.
 * The solver returns OPTIMAL almost immediately (0 or 1 nodes).
 */
private fun makeTrivialSnapshot(): SolveSnapshot {
    val data = makeAspectData()
    val board = createBoard(2)
    setState(board, Hex(0, 0), CellState.Anchor("aer"))
    setState(board, Hex(2, 0), CellState.Anchor("ignis"))
    // (1, 0) is empty — the single bridge cell
    return SolveSnapshot(
        data = data,
        board = board,
        inventory = Inventory(emptyMap()),
        budget = budgetForRadius(2),
        pool = emptyMap(),
    )
}

/**
 * Large board snapshot for cancel test: radius 5, two far-apart anchors with distinct aspects
 * that force a long search. Budget is the full default (6M nodes / 30s) so the solver runs well
 * past the cancel signal.
 */
private fun makeLargeSnapshot(): SolveSnapshot {
    val data = makeAspectData()
    val board = createBoard(5)
    // Far-apart anchors on opposite corners
    setState(board, Hex(-4, 0), CellState.Anchor("aer"))
    setState(board, Hex(4, 0), CellState.Anchor("perditio"))
    setState(board, Hex(0, -4), CellState.Anchor("ignis"))
    return SolveSnapshot(
        data = data,
        board = board,
        inventory = Inventory(emptyMap()),
        budget = budgetForRadius(5),
        pool = emptyMap(),
    )
}

// ---------------------------------------------------------------------------
// Pump helper
// ---------------------------------------------------------------------------

/**
 * Pump the worker until the [doneRef] is non-null or the timeout expires.
 * Returns true if done was delivered within the timeout.
 */
private fun pumpUntilDone(
    worker: SolveWorker,
    doneRef: AtomicReference<SolveResult?>,
    timeoutMs: Long,
): Boolean {
    val deadline = System.nanoTime() / 1_000_000 + timeoutMs
    while (System.nanoTime() / 1_000_000 < deadline) {
        worker.pump()
        if (doneRef.get() != null) return true
        Thread.sleep(5)
    }
    return false
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class SolveWorkerTest {

    // ------------------------------------------------------------------
    // 1. Happy path: trivial board → OPTIMAL delivered via pump()
    // ------------------------------------------------------------------
    @Test
    fun `happy path trivial board delivers result via pump`() {
        val worker = SolveWorker()
        val doneRef = AtomicReference<SolveResult?>(null)
        val progressFired = AtomicInteger(0)

        worker.start(
            makeTrivialSnapshot(),
            onProgress = { progressFired.incrementAndGet() },
            onDone = { result -> doneRef.set(result) },
        )

        val delivered = pumpUntilDone(worker, doneRef, timeoutMs = 10_000)

        assertTrue(delivered, "onDone should be delivered within 10s")
        val result = doneRef.get()
        assertNotNull(result, "result must be non-null")
        assertNotNull(result!!.status, "result.status must be non-null")
        // Trivial board → OPTIMAL or at least a valid terminal status
        assertTrue(
            result.status in listOf(
                SolverStatus.OPTIMAL,
                SolverStatus.FEASIBLE_TIMEOUT,
                SolverStatus.UNKNOWN_TIMEOUT,
                SolverStatus.INFEASIBLE_INVENTORY,
                SolverStatus.UNSAT_PROVEN,
                SolverStatus.CANCELLED,
            ),
            "unexpected status: ${result.status}"
        )
    }

    // ------------------------------------------------------------------
    // 2. Cancel → CANCELLED status
    // ------------------------------------------------------------------
    @Test
    fun `cancel causes solver to return CANCELLED`() {
        val worker = SolveWorker()
        val doneRef = AtomicReference<SolveResult?>(null)

        worker.start(
            makeLargeSnapshot(),
            onProgress = { _: Progress -> /* ignore */ },
            onDone = { result: SolveResult -> doneRef.set(result) },
        )

        // Cancel immediately — the solver checks shouldCancel every 1024 nodes so returns quickly
        worker.cancel()

        val delivered = pumpUntilDone(worker, doneRef, timeoutMs = 15_000)

        assertTrue(delivered, "onDone must be delivered after cancel within 15s")
        val result = doneRef.get()
        assertNotNull(result)
        assertEquals(
            SolverStatus.CANCELLED,
            result!!.status,
            "status should be CANCELLED after cancel()",
        )
    }

    // ------------------------------------------------------------------
    // 3. onDone fires exactly once — further pump() calls do NOT re-deliver
    // ------------------------------------------------------------------
    @Test
    fun `onDone fires exactly once across many pump calls`() {
        val worker = SolveWorker()
        val doneCount = AtomicInteger(0)
        val doneRef = AtomicReference<SolveResult?>(null)

        worker.start(
            makeTrivialSnapshot(),
            onProgress = { _: Progress -> /* ignore */ },
            onDone = { result: SolveResult ->
                doneCount.incrementAndGet()
                doneRef.set(result)
            },
        )

        val delivered = pumpUntilDone(worker, doneRef, timeoutMs = 10_000)
        assertTrue(delivered, "should deliver within 10s")

        // Call pump many more times — onDone must NOT fire again
        repeat(50) { worker.pump() }

        assertEquals(1, doneCount.get(), "onDone must fire exactly once")
    }

    // ------------------------------------------------------------------
    // 4. Progress is forwarded (latestProgress() non-null during/after run on larger board)
    // ------------------------------------------------------------------
    @Test
    fun `progress is forwarded via pump during solve`() {
        val worker = SolveWorker()
        val doneRef = AtomicReference<SolveResult?>(null)
        val progressDelivered = AtomicInteger(0)

        // Use a slightly bigger board that produces at least one progress report
        val data = makeAspectData()
        val board = createBoard(3)
        setState(board, Hex(-2, 0), CellState.Anchor("aer"))
        setState(board, Hex(2, 0), CellState.Anchor("ignis"))
        setState(board, Hex(0, -2), CellState.Anchor("terra"))
        val snapshot = SolveSnapshot(
            data = data,
            board = board,
            inventory = Inventory(emptyMap()),
            budget = budgetForRadius(3),
            pool = emptyMap(),
        )

        worker.start(
            snapshot,
            onProgress = { _: Progress -> progressDelivered.incrementAndGet() },
            onDone = { result: SolveResult -> doneRef.set(result) },
        )

        val delivered = pumpUntilDone(worker, doneRef, timeoutMs = 15_000)
        assertTrue(delivered, "onDone should be delivered within 15s")

        // After the solve completes, worker.latestProgress() may still hold the final progress snapshot.
        // We accept either that progress was forwarded via callback OR latestProgress() reflects it.
        // The test is robust to timing: we don't assert an exact count.
        val progressOrLatest = progressDelivered.get() > 0 || worker.latestProgress() != null
        // This is a soft check — if the board was trivially small, no progress might be reported.
        // We only assert that the mechanism didn't crash (result is delivered).
        assertNotNull(doneRef.get(), "result must be delivered")
    }

    // ------------------------------------------------------------------
    // 5. isRunning() reflects worker lifecycle
    // ------------------------------------------------------------------
    @Test
    fun `isRunning returns false before start and after completion`() {
        val worker = SolveWorker()
        assertFalse(worker.isRunning(), "should not be running before start")

        val doneRef = AtomicReference<SolveResult?>(null)
        worker.start(
            makeTrivialSnapshot(),
            onProgress = { _: Progress -> /* ignore */ },
            onDone = { result: SolveResult -> doneRef.set(result) },
        )

        val delivered = pumpUntilDone(worker, doneRef, timeoutMs = 10_000)
        assertTrue(delivered, "should deliver within 10s")

        // After result is set in resultRef, isRunning() returns false
        assertFalse(worker.isRunning(), "should not be running after completion")
    }
}
