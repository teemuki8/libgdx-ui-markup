package dev.gdx.markup.idea

import com.intellij.openapi.Disposable
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the preview child process for one project's tool window. Implements the IntelliJ
 * [Disposable] boundary so the project disposal path terminates the child, and exposes
 * non-blocking [replace] / [dispose] scheduling: both only enqueue work on a dedicated
 * background executor and never wait, regardless of the calling thread (the EDT included).
 *
 * All process-ownership behavior lives in [PreviewProcessSupervisor], which is plain JVM and
 * driven by the unit tests through its internal constructor.
 */
class PreviewProcessOwner(
    onStatus: (MarkupStatusLine) -> Unit,
    onProse: (String) -> Unit,
    onStderr: (String) -> Unit,
    onExit: (ExitCause) -> Unit,
) : Disposable {
    private val supervisor = PreviewProcessSupervisor.default(onStatus, onProse, onStderr, onExit)

    /** Schedules a switch to [command]; returns immediately without waiting. */
    fun replace(command: List<String>) = supervisor.replace(command)

    /** Terminates the child, stops the readers, and shuts the owner down; idempotent. */
    override fun dispose() = supervisor.dispose()
}

/** Why a child's streams drained: it exited on its own, or the owner terminated it. */
enum class ExitCause { SELF, TERMINATED }

/** Result of one bounded wait against a child process. */
internal enum class WaitOutcome { EXITED, TIMED_OUT, INTERRUPTED }

/**
 * Bounded wait policy: returns whether [Process] exited (or was interrupted) by the given
 * monotonic deadline in nanoseconds.
 */
internal typealias WaitPolicy = (Process, Long) -> WaitOutcome

/**
 * Plain-JVM single-owner process engine backing [PreviewProcessOwner]. Serializes every
 * lifecycle mutation on the injected single-thread [executor]: at most one child is active,
 * and a [replace] first terminates the previous child through the full escalation ladder —
 * close stdin, destroy, bounded wait, destroyForcibly, final wait — while restoring any
 * interrupt status the terminating thread had. Both stdout and stderr are drained on their
 * own daemon threads (typed `markup-status` lines routed to [onStatus], everything else to
 * [onProse]/[onStderr]); when both drains finish the [onExit] callback fires exactly once per
 * child. No sleeps anywhere: every wait is a bounded monotonic wait.
 */
internal class PreviewProcessSupervisor internal constructor(
    private val launcher: (List<String>) -> Process,
    private val executor: ExecutorService,
    private val nowNanos: () -> Long,
    private val waitUntilExited: WaitPolicy,
    private val onStatus: (MarkupStatusLine) -> Unit,
    private val onProse: (String) -> Unit,
    private val onStderr: (String) -> Unit,
    private val onExit: (ExitCause) -> Unit,
) {
    @Volatile
    private var active: ActiveChild? = null

    /** Set synchronously by [dispose]; visible to every queued task via the volatile write. */
    @Volatile
    private var disposed = false

    /** Schedules a switch to [command]; non-blocking and never throws. */
    fun replace(command: List<String>) {
        try {
            executor.execute {
                if (disposed) return@execute
                val terminated = terminateActive()
                // Never launch a replacement unless the previous child is confirmed dead,
                // and never launch once dispose has been called (a task may be mid-flight).
                if (terminated != TerminateOutcome.EXITED || disposed) return@execute
                launch(command)
            }
        } catch (_: RejectedExecutionException) {
            // disposed concurrently; the launch is dropped
        }
    }

    /** Terminates the child and shuts the executor down; non-blocking and idempotent. */
    fun dispose() {
        disposed = true
        try {
            executor.execute {
                // Keep owning the child: retry the termination ladder until it is confirmed
                // dead. Each round is paced by the bounded waits inside terminateProcess, so
                // the retry never spins and a live child is never dropped.
                while (terminateActive() == TerminateOutcome.STILL_ALIVE) {
                    // retry on the next round
                }
                executor.shutdown()
            }
        } catch (_: RejectedExecutionException) {
            // already disposed
        }
    }

    /** Terminates the active child; keeps ownership (and reports) when it survives the ladder. */
    private fun terminateActive(): TerminateOutcome {
        val child = active ?: return TerminateOutcome.EXITED
        active = null
        closeStdin(child.process)
        val result = terminateProcess(child.process)
        var interrupted = result.interrupted
        if (result.exited) {
            interrupted = awaitDrains(child, nowNanos() + DRAIN_JOIN_NANOS, interrupted)
        } else {
            // The child survived destroy + bounded force-kill retries. It is still running,
            // so ownership is preserved for a later retry and the failure is reported.
            active = child
            onStderr("failed to terminate preview process; it is still running")
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        return if (result.exited) TerminateOutcome.EXITED else TerminateOutcome.STILL_ALIVE
    }

    /**
     * destroy → bounded wait → destroyForcibly + bounded wait (bounded retry) → confirmed
     * exit or failure. The interrupt flag from any wait is reported, never swallowed.
     */
    private fun terminateProcess(process: Process): TerminateResult {
        process.destroy()
        var interrupted = false
        var outcome = waitUntilExited(process, nowNanos() + GRACEFUL_TERMINATE_NANOS)
        if (outcome == WaitOutcome.INTERRUPTED) {
            interrupted = true
        }
        var exited = outcome == WaitOutcome.EXITED
        var attempts = 0
        while (!exited && attempts < FORCE_KILL_ATTEMPTS) {
            process.destroyForcibly()
            attempts++
            outcome = waitUntilExited(process, nowNanos() + FORCE_TERMINATE_NANOS)
            if (outcome == WaitOutcome.INTERRUPTED) {
                interrupted = true
            }
            exited = outcome == WaitOutcome.EXITED
        }
        return TerminateResult(exited, interrupted)
    }

    /** Bounded drain join; an interrupt during the join wins and is reported, never looped. */
    private fun awaitDrains(child: ActiveChild, deadlineNanos: Long, interrupted: Boolean): Boolean {
        var wasInterrupted = interrupted
        while (child.drainLatch.count > 0L) {
            val remaining = deadlineNanos - nowNanos()
            if (remaining <= 0) {
                return wasInterrupted
            }
            try {
                if (child.drainLatch.await(remaining, TimeUnit.NANOSECONDS)) {
                    return wasInterrupted
                }
            } catch (interruptedWait: InterruptedException) {
                wasInterrupted = true
                return wasInterrupted
            }
        }
        return wasInterrupted
    }

    private fun launch(command: List<String>) {
        val process = try {
            launcher(command)
        } catch (failure: Exception) {
            onStderr("failed to launch preview: ${failure.message}")
            return
        }
        val child = ActiveChild(process)
        active = child
        Thread.ofPlatform().name("markup-preview-stdout").daemon().start {
            drain(child, process.inputStream, ::forwardStdout)
        }
        Thread.ofPlatform().name("markup-preview-stderr").daemon().start {
            drain(child, process.errorStream, ::forwardStderr)
        }
    }

    private fun drain(child: ActiveChild, stream: InputStream, emit: (String) -> Unit) {
        try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (active !== child) return@useLines
                    emit(line)
                }
            }
        } catch (_: IOException) {
            // process died; termination joins the drains
        } finally {
            child.drainLatch.countDown()
            if (child.remainingDrains.decrementAndGet() == 0) {
                val selfExited = active === child
                if (selfExited) {
                    active = null
                }
                onExit(if (selfExited) ExitCause.SELF else ExitCause.TERMINATED)
            }
        }
    }

    private fun forwardStdout(line: String) {
        val parsed = MarkupStatusLineParser.parse(line)
        if (parsed != null) onStatus(parsed) else onProse(line)
    }

    private fun forwardStderr(line: String) = onStderr(line)

    private fun closeStdin(process: Process) {
        try {
            process.outputStream.close()
        } catch (_: IOException) {
            // stream already closed
        }
    }

    private class ActiveChild(val process: Process) {
        val drainLatch = CountDownLatch(2)
        val remainingDrains = AtomicInteger(2)
    }

    private class TerminateResult(val exited: Boolean, val interrupted: Boolean)

    private enum class TerminateOutcome { EXITED, STILL_ALIVE }

    companion object {
        private const val GRACEFUL_TERMINATE_NANOS = 2_000_000_000L
        private const val FORCE_TERMINATE_NANOS = 2_000_000_000L
        private const val DRAIN_JOIN_NANOS = 5_000_000_000L
        private const val FORCE_KILL_ATTEMPTS = 2

        /** Production wiring used by [PreviewProcessOwner]. */
        internal fun default(
            onStatus: (MarkupStatusLine) -> Unit,
            onProse: (String) -> Unit,
            onStderr: (String) -> Unit,
            onExit: (ExitCause) -> Unit,
        ): PreviewProcessSupervisor = PreviewProcessSupervisor(
            launcher = { command -> ProcessBuilder(command).start() },
            executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "markup-preview-owner").apply { isDaemon = true }
            },
            nowNanos = System::nanoTime,
            waitUntilExited = defaultWait(System::nanoTime),
            onStatus = onStatus,
            onProse = onProse,
            onStderr = onStderr,
            onExit = onExit,
        )

        /** Production bounded wait: waitFor until the deadline, then report the live state. */
        internal fun defaultWait(nowNanos: () -> Long): WaitPolicy {
            fun wait(process: Process, deadline: Long): WaitOutcome {
                while (true) {
                    val remaining = deadline - nowNanos()
                    if (remaining <= 0) {
                        return if (process.isAlive) WaitOutcome.TIMED_OUT else WaitOutcome.EXITED
                    }
                    try {
                        if (process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                            return WaitOutcome.EXITED
                        }
                    } catch (interrupted: InterruptedException) {
                        return WaitOutcome.INTERRUPTED
                    }
                }
            }
            return ::wait
        }
    }
}
