package io.github.muindor.tcresearchsolver.ui

import io.github.muindor.tcresearchsolver.solver.*

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

/** Immutable snapshot of solver inputs, built on the client thread before solving. */
data class SolveSnapshot(
    val data: AspectData,
    val board: Board,
    val inventory: Inventory,
    val budget: SolveBudget,
    val pool: Map<Aspect, Int>,
    /** Fast mode: stop at the first valid solution instead of proving optimality. */
    val fast: Boolean = false,
)

// ---------------------------------------------------------------------------
// Apply report
// ---------------------------------------------------------------------------

/**
 * Result of applying a plan.
 *
 * Success = [abortReason] is null AND [rejectedCells] is empty.
 * Abort   = [abortReason] is non-null (e.g. "out of ink"; cells unknown, may be empty).
 * Partial = [abortReason] is null but [rejectedCells] is non-empty (server rejected some cells).
 */
data class ApplyReport(val rejectedCells: Set<String>, val abortReason: String? = null)

// ---------------------------------------------------------------------------
// Port interfaces (injected fakes in tests; real impls in later tasks)
// ---------------------------------------------------------------------------

/** Worker abstraction (real impl = SolveWorker, Task 4.3). */
interface SolveWorkerPort {
    fun start(snapshot: SolveSnapshot, onProgress: (Progress) -> Unit, onDone: (SolveResult) -> Unit)
    fun cancel()
}

/** Applier abstraction (real impl wraps planApply + Applier.apply + postVerify). */
interface ApplierPort {
    fun apply(result: SolveResult, snapshot: SolveSnapshot, onDone: (ApplyReport) -> Unit)
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

sealed class SolveState {
    object Idle : SolveState()
    data class Solving(val progress: Progress?) : SolveState()
    data class Preview(val result: SolveResult) : SolveState()
    object Applying : SolveState()
    object Done : SolveState()
    data class Error(val message: String) : SolveState()
}

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------

class SolveController(
    val worker: SolveWorkerPort,
    val applier: ApplierPort,
    val previewConfirm: Boolean,
) {

    /** Current state — always read on the client thread. */
    var state: SolveState = SolveState.Idle
        private set

    /**
     * Caller sets/refreshes this each GUI tick (or before calling start()).
     * [start] requires it non-null and solvable.
     */
    var snapshot: SolveSnapshot? = null

    // ------------------------------------------------------------------
    // start
    // ------------------------------------------------------------------

    fun start() {
        if (state !is SolveState.Idle) return
        val snap = snapshot ?: return
        if (!isSolvable(snap)) return

        state = SolveState.Solving(null)
        worker.start(snap, ::onProgress, ::onSolved)
    }

    private fun isSolvable(snap: SolveSnapshot): Boolean {
        // Must have at least one anchor
        if (anchorCells(snap.board).isEmpty()) return false
        // Must have at least one Empty cell to fill
        for (h in boardCells(snap.board.radius)) {
            if (getState(snap.board, h) is CellState.Empty) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // onProgress (callback from worker thread; must be client-thread-safe
    // in the real impl; here it's called directly in tests)
    // ------------------------------------------------------------------

    fun onProgress(p: Progress) {
        if (state !is SolveState.Solving) return
        state = SolveState.Solving(p)
    }

    // ------------------------------------------------------------------
    // onSolved (callback from worker)
    // ------------------------------------------------------------------

    fun onSolved(result: SolveResult) {
        if (state !is SolveState.Solving) return // stale callback (e.g. after cancel)

        when (result.status) {
            SolverStatus.OPTIMAL,
            SolverStatus.FEASIBLE_TIMEOUT -> {
                if (previewConfirm) {
                    state = SolveState.Preview(result)
                } else {
                    // Auto-apply: skip Preview
                    state = SolveState.Applying
                    applier.apply(result, snapshot!!, ::onApplied)
                }
            }

            SolverStatus.CANCELLED -> {
                state = SolveState.Idle
            }

            SolverStatus.INFEASIBLE_INVENTORY -> {
                state = SolveState.Error(
                    result.message ?: "No solution: inventory insufficient (INFEASIBLE_INVENTORY)"
                )
            }

            SolverStatus.UNSAT_PROVEN -> {
                state = SolveState.Error(
                    result.message ?: "No solution: proven unsatisfiable (UNSAT_PROVEN)"
                )
            }

            SolverStatus.UNKNOWN_TIMEOUT -> {
                state = SolveState.Error(
                    result.message ?: "No solution found within time budget (UNKNOWN_TIMEOUT)"
                )
            }

            SolverStatus.INVALID_INPUT -> {
                state = SolveState.Error(
                    result.message ?: "Invalid solver input (INVALID_INPUT)"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // cancel
    // ------------------------------------------------------------------

    fun cancel() {
        if (state !is SolveState.Solving) return
        worker.cancel()
        state = SolveState.Idle
        // A subsequent onSolved(CANCELLED) will be ignored because state is no longer Solving.
    }

    // ------------------------------------------------------------------
    // apply
    // ------------------------------------------------------------------

    fun apply() {
        val s = state
        if (s !is SolveState.Preview) return
        val result = s.result
        state = SolveState.Applying
        applier.apply(result, snapshot!!, ::onApplied)
    }

    // ------------------------------------------------------------------
    // onApplied (callback from applier)
    // ------------------------------------------------------------------

    fun onApplied(report: ApplyReport) {
        if (state != SolveState.Applying) return
        state = when {
            report.abortReason != null     -> SolveState.Error(report.abortReason)
            report.rejectedCells.isEmpty() -> SolveState.Done
            else -> SolveState.Error(
                "server rejected ${report.rejectedCells.size} cell(s): " +
                    report.rejectedCells.sorted().joinToString()
            )
        }
    }

    // ------------------------------------------------------------------
    // reset
    // ------------------------------------------------------------------

    fun reset() {
        if (state is SolveState.Solving) worker.cancel()
        state = SolveState.Idle
    }

    // ------------------------------------------------------------------
    // Render getters
    // ------------------------------------------------------------------

    fun buttonLabel(): String = when (state) {
        is SolveState.Idle -> "Solve"
        is SolveState.Solving -> "Cancel"
        is SolveState.Preview -> "Apply"
        is SolveState.Applying -> "Applying…" // "Applying…"
        is SolveState.Done -> "Reset"
        is SolveState.Error -> "Reset"
    }

    fun progressText(): String? {
        val s = state as? SolveState.Solving ?: return null
        val p = s.progress ?: return null
        val secs = p.timeMs / 1000
        val base = "⏱ ${secs}s · nodes ${p.nodes}" // "⏱ Xs · nodes N"
        return if (p.best != null) {
            "$base · best ${p.best.cells.toInt()}"
        } else {
            base
        }
    }

    fun onButtonClicked() {
        when (state) {
            is SolveState.Idle -> start()
            is SolveState.Solving -> cancel()
            is SolveState.Preview -> apply()
            is SolveState.Applying -> { /* no-op */ }
            is SolveState.Done -> reset()
            is SolveState.Error -> reset()
        }
    }
}
