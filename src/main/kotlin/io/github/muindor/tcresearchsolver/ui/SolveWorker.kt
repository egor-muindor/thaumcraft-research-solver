package io.github.muindor.tcresearchsolver.ui

import io.github.muindor.tcresearchsolver.solver.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Off-thread solver runner implementing [SolveWorkerPort].
 *
 * Threading contract:
 *  - The background thread (started in [start]) only touches the atomics
 *    ([cancelFlag], [latestProgress], [resultRef], [generation]) — never invokes callbacks directly.
 *  - [pump] is called on the CLIENT THREAD each tick. It coalesces progress updates,
 *    delivers [onDoneCb] exactly once when the *current* solve completes, and refreshes the
 *    orphan-detection heartbeat.
 *  - [cancel] may be called from any thread; it sets [cancelFlag] which the solver polls via
 *    [SolveOptions.shouldCancel] every 1 024 nodes.
 *
 * Generation guard (fixes the cancel-then-restart race): every [start] bumps [generation] and
 * captures it as the run's `gen`. A later [start] increments [generation], which (a) makes the
 * older thread's `shouldCancel` return true so it stops promptly, and (b) causes [pump] to discard
 * any progress/result stamped with a stale generation. This prevents a lingering previous solve
 * from splicing a stale-snapshot result into a new run.
 *
 * Orphan guard (fixes solves leaking when the GUI closes): [pump] refreshes [lastPumpMs]; if it
 * stops being called (the GUI closed, so no component ticks), the solve self-cancels after
 * [ORPHAN_TIMEOUT_MS]. Under normal operation pump runs every frame, so this never fires.
 *
 * @param now Injected clock (millis). Default: [System.nanoTime]/1_000_000. Injectable for tests.
 */
class SolveWorker(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : SolveWorkerPort {

    private val cancelFlag = AtomicBoolean(false)

    /** Run counter; bumped per [start]. The active run's value is mirrored in [currentGen]. */
    private val generation = AtomicInteger(0)

    /** The generation [pump] currently delivers for (the most recent [start]). */
    @Volatile private var currentGen = 0

    /** Most-recent [Progress] snapshot stashed by the background thread; drained by [pump]. */
    private val latestProgress = AtomicReference<Progress?>(null)

    /** Set once by the background thread when [solveWithValidation] returns — stamped with its generation. */
    private val resultRef = AtomicReference<Pair<Int, SolveResult>?>(null)

    /** Prevents [onDoneCb] from being invoked more than once across repeated [pump] calls. */
    private val resultDelivered = AtomicBoolean(false)

    /** Millis of the last [pump] call; the background thread self-cancels if this goes stale. */
    @Volatile private var lastPumpMs = 0L

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
        // New run: bump the generation (auto-cancels any older live thread via shouldCancel below)
        // and reset shared state for this generation.
        val gen = generation.incrementAndGet()
        currentGen = gen
        cancelFlag.set(false)
        latestProgress.set(null)
        resultRef.set(null)
        resultDelivered.set(false)
        lastPumpMs = now()
        onProgressCb = onProgress
        onDoneCb = onDone

        val opts = SolveOptions(
            data = snapshot.data,
            board = snapshot.board,
            inventory = snapshot.inventory,
            budget = snapshot.budget,
            // Background thread: only touch atomics, and only for THIS generation.
            onProgress = { p -> if (generation.get() == gen) latestProgress.set(p) },
            // Cancel when: explicitly cancelled, superseded by a newer start, or orphaned (GUI closed).
            shouldCancel = {
                cancelFlag.get() ||
                    generation.get() != gen ||
                    (now() - lastPumpMs > ORPHAN_TIMEOUT_MS)
            },
            now = now,
        )

        val t = Thread(
            {
                val result = solveWithValidation(opts)
                // Publish only if still the current generation (else a newer run owns the fields).
                if (generation.get() == gen) resultRef.set(gen to result)
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
     * - Refreshes the orphan heartbeat ([lastPumpMs]).
     * - While the current solve runs: forwards the latest coalesced [Progress] to [onProgressCb].
     * - Once the current solve completes: invokes [onDoneCb] exactly once, then becomes a no-op.
     * - Discards any result/progress stamped with a stale generation.
     *
     * Safe to call when idle (no-op).
     */
    fun pump() {
        lastPumpMs = now()
        val done = resultRef.get()
        if (done != null && done.first == currentGen) {
            if (resultDelivered.compareAndSet(false, true)) {
                onDoneCb?.invoke(done.second)
            }
            return
        }
        // Drain the latest progress update (getAndSet(null) coalesces rapid updates)
        latestProgress.getAndSet(null)?.let { p -> onProgressCb?.invoke(p) }
    }

    // ------------------------------------------------------------------
    // State queries (may be polled on the client thread or in tests)
    // ------------------------------------------------------------------

    /** True while the background thread is alive and the current generation has no result yet. */
    fun isRunning(): Boolean {
        if (thread?.isAlive != true) return false
        val done = resultRef.get()
        return done == null || done.first != currentGen
    }

    /** The most recent [Progress] snapshot stored by the background thread; may be null. */
    fun latestProgress(): Progress? = latestProgress.get()

    companion object {
        /** If [pump] is not called for this many millis, an in-flight solve self-cancels (GUI closed). */
        const val ORPHAN_TIMEOUT_MS = 3_000L
    }
}
