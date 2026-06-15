package io.github.muindor.tcresearchsolver.ui

import io.github.muindor.tcresearchsolver.solver.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Off-thread solver runner implementing [SolveWorkerPort].
 *
 * Threading contract:
 *  - Background thread (started in [start]) only touches the three atomics
 *    ([cancelFlag], [latestProgress], [resultRef]) — never invokes callbacks directly.
 *  - [pump] is called on the CLIENT THREAD each tick. It coalesces progress updates
 *    and delivers [onDoneCb] exactly once when the solve completes.
 *  - [cancel] may be called from any thread; it sets [cancelFlag] which is polled
 *    by the solver via [SolveOptions.shouldCancel] every 1 024 nodes.
 *
 * @param now Injected clock (nanos → millis). Default: [System.nanoTime]/1_000_000.
 *            Kept injectable so unit tests can supply a controlled clock.
 */
class SolveWorker(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : SolveWorkerPort {

    private val cancelFlag = AtomicBoolean(false)

    /** Most-recent [Progress] snapshot stashed by the background thread; drained by [pump]. */
    private val latestProgress = AtomicReference<Progress?>(null)

    /** Set once by the background thread when [solveWithValidation] returns. */
    private val resultRef = AtomicReference<SolveResult?>(null)

    /** Prevents [onDoneCb] from being invoked more than once across repeated [pump] calls. */
    private val resultDelivered = AtomicBoolean(false)

    @Volatile private var thread: Thread? = null
    @Volatile private var onProgressCb: ((Progress) -> Unit)? = null
    @Volatile private var onDoneCb: ((SolveResult) -> Unit)? = null

    // ------------------------------------------------------------------
    // SolveWorkerPort
    // ------------------------------------------------------------------

    override fun start(
        snapshot: SolveSnapshot,
        onProgress: (Progress) -> Unit,
        onDone: (SolveResult) -> Unit,
    ) {
        // Reset state for a fresh run (single-use guard: caller must not call start twice concurrently)
        cancelFlag.set(false)
        latestProgress.set(null)
        resultRef.set(null)
        resultDelivered.set(false)
        onProgressCb = onProgress
        onDoneCb = onDone

        val opts = SolveOptions(
            data = snapshot.data,
            board = snapshot.board,
            inventory = snapshot.inventory,
            budget = snapshot.budget,
            // Background thread stashes progress into the atomic; pump() delivers to the callback
            onProgress = { p -> latestProgress.set(p) },
            shouldCancel = { cancelFlag.get() },
            now = now,
        )

        val t = Thread(
            {
                val result = solveWithValidation(opts)
                resultRef.set(result)
            },
            "tcresearchsolver-solve",
        )
        t.isDaemon = true
        thread = t
        t.start()
    }

    override fun cancel() {
        cancelFlag.set(true)
    }

    // ------------------------------------------------------------------
    // Client-thread pump
    // ------------------------------------------------------------------

    /**
     * Call on the CLIENT THREAD each tick.
     *
     * - While the solve is running: forwards the latest coalesced [Progress] to [onProgressCb].
     * - Once the solve completes: invokes [onDoneCb] exactly once, then becomes a no-op.
     *
     * Safe to call when idle (no-op).
     */
    fun pump() {
        val done = resultRef.get()
        if (done != null) {
            if (resultDelivered.compareAndSet(false, true)) {
                onDoneCb?.invoke(done)
            }
            return
        }
        // Drain the latest progress update (getAndSet(null) coalesces rapid updates)
        latestProgress.getAndSet(null)?.let { p -> onProgressCb?.invoke(p) }
    }

    // ------------------------------------------------------------------
    // State queries (may be polled on the client thread or in tests)
    // ------------------------------------------------------------------

    /** True while the background thread is alive and no result has been stored yet. */
    fun isRunning(): Boolean = thread?.isAlive == true && resultRef.get() == null

    /** The most recent [Progress] snapshot stored by the background thread; may be null. */
    fun latestProgress(): Progress? = latestProgress.get()
}
