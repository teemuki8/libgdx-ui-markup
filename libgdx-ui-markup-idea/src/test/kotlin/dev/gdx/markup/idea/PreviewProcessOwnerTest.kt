package dev.gdx.markup.idea

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-JVM tests for the process-ownership engine ([PreviewProcessSupervisor]) that backs
 * [PreviewProcessOwner]. The owner itself implements the IntelliJ `Disposable` boundary and is
 * only loadable inside the IDE classpath, so these tests drive the engine through its internal
 * constructor with a fake launcher, a captured single-thread executor, an injected wait policy,
 * and recording stream consumers. Every scenario ends with `dispose()` and an
 * `awaitTermination` so a passing test proves no child or reader thread survives.
 */
class PreviewProcessOwnerTest {

    @Test
    fun noisyStreamsAreDrainedConcurrentlyAndTypedStatusSeparated() {
        val fake = FakeProcess()
        val launcher = FakeLauncher(listOf(fake))
        val status = CopyOnWriteArrayList<MarkupStatusLine>()
        val prose = CopyOnWriteArrayList<String>()
        val err = CopyOnWriteArrayList<String>()
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val statusLatch = CountDownLatch(200)
        val proseLatch = CountDownLatch(150)
        val errLatch = CountDownLatch(120)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = defaultFakePolicy(),
            onStatus = {
                status += it
                statusLatch.countDown()
            },
            onProse = {
                prose += it
                proseLatch.countDown()
            },
            onStderr = {
                err += it
                errLatch.countDown()
            },
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("preview"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS), "first launch")
            repeat(200) { fake.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":$it}\n") }
            repeat(150) { fake.writeStdout("[LWJGL] noise $it\n") }
            repeat(120) { fake.writeStderr("stderr line $it\n") }
            // Every written line must be consumed by the drains before the child is killed.
            assertTrue(statusLatch.await(5, TimeUnit.SECONDS), "all status lines drained")
            assertTrue(proseLatch.await(5, TimeUnit.SECONDS), "all prose lines drained")
            assertTrue(errLatch.await(5, TimeUnit.SECONDS), "all stderr lines drained")
            disposeAndAssertNoThreads(supervisor, executor)
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "exit must fire after both drains")
            assertEquals(200, status.size, "every typed status line must arrive")
            assertEquals(150, prose.size, "every non-status stdout line must arrive")
            assertEquals(120, err.size, "every stderr line must arrive")
            assertEquals(200, status.map { it.nodes }.distinct().size, "nodes must be typed fields")
            assertEquals(150, prose.filterIndexed { i, line -> line == "[LWJGL] noise $i" }.size)
            assertEquals(120, err.filterIndexed { i, line -> line == "stderr line $i" }.size)
            assertEquals(listOf(ExitCause.TERMINATED), exits)
        } finally {
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun gracefulSelfExitQuiescesWithoutForcedKill() {
        val fake = FakeProcess()
        val launcher = FakeLauncher(listOf(fake, FakeProcess()), expectedLaunches = 2)
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val secondStatus = CountDownLatch(1)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = defaultFakePolicy(),
            onStatus = { if (it.nodes == 7) secondStatus.countDown() },
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            fake.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":1}\n")
            fake.selfExit()
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "self exit must be observed")
            assertEquals(listOf(ExitCause.SELF), exits)
            assertEquals(0, fake.destroyCalls.get(), "no termination was needed")
            assertEquals(0, fake.forceKillCalls.get())
            // A later replace starts a fresh child without touching the dead one.
            supervisor.replace(listOf("second"))
            assertTrue(launcher.allLaunched.await(5, TimeUnit.SECONDS))
            assertEquals(0, fake.destroyCalls.get(), "dead child is never re-terminated")
            launcher.launched[1].writeStdout(
                "markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":7}\n")
            assertTrue(secondStatus.await(5, TimeUnit.SECONDS), "new child must forward status")
            disposeAndAssertNoThreads(supervisor, executor)
        } finally {
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun hungProcessIsForceKilledInOrderOnReplacement() {
        val fake1 = FakeProcess(gracefulExit = false)
        val fake2 = FakeProcess()
        val launcher = FakeLauncher(listOf(fake1, fake2), expectedLaunches = 2)
        val calls = CopyOnWriteArrayList<String>()
        fake1.onDestroy = { calls += "destroy" }
        fake1.onForceKill = { calls += "destroyForcibly" }
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val secondStatus = CountDownLatch(1)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = defaultFakePolicy(),
            onStatus = { if (it.nodes == 3) secondStatus.countDown() },
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            fake1.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":1}\n")
            supervisor.replace(listOf("second"))
            assertTrue(launcher.allLaunched.await(5, TimeUnit.SECONDS), "second launch after kill")
            assertEquals(listOf("destroy", "destroyForcibly"), calls,
                "escalation must be destroy then destroyForcibly")
            assertEquals(1, fake1.forceKillCalls.get())
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "replaced child exit observed")
            assertTrue(exits.contains(ExitCause.TERMINATED), "replaced child exits as terminated")
            fake2.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":3}\n")
            assertTrue(secondStatus.await(5, TimeUnit.SECONDS), "new child must forward status")
            disposeAndAssertNoThreads(supervisor, executor)
        } finally {
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun interruptedWaitStillEscalatesAndPreservesInterrupt() {
        val fake1 = FakeProcess(gracefulExit = false)
        val fake2 = FakeProcess()
        val launcher = FakeLauncher(listOf(fake1, fake2), expectedLaunches = 2)
        val threadRef = AtomicReference<Thread>()
        val executor = newExecutor(threadRef)
        val waitStarted = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
        val wait: WaitPolicy = { process, _ ->
            val fake = process as? FakeProcess
            if (fake != null && fake.forceKillCalls.get() > 0) {
                WaitOutcome.EXITED
            } else {
                waitStarted.countDown()
                try {
                    releaseWait.await(10, TimeUnit.SECONDS)
                    WaitOutcome.EXITED
                } catch (interrupted: InterruptedException) {
                    WaitOutcome.INTERRUPTED
                }
            }
        }
        val exits = CopyOnWriteArrayList<ExitCause>()
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = wait,
            onExit = { exits += it },
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            supervisor.replace(listOf("second"))
            assertTrue(waitStarted.await(5, TimeUnit.SECONDS), "wait must start")
            threadRef.get().interrupt()
            releaseWait.countDown()
            assertTrue(launcher.allLaunched.await(5, TimeUnit.SECONDS),
                "escalation must complete despite the interrupt")
            assertEquals(1, fake1.forceKillCalls.get(), "hung child is still force-killed")
            assertTrue(threadRef.get().isInterrupted, "interrupt status must be preserved")
            disposeAndAssertNoThreads(supervisor, executor)
        } finally {
            releaseWait.countDown()
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun replaceFromEdtReturnsWithoutWaiting() {
        val fake1 = FakeProcess()
        val fake2 = FakeProcess()
        val launcher = FakeLauncher(listOf(fake1, fake2), expectedLaunches = 2)
        val waitStarted = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
        val wait: WaitPolicy = { process, _ ->
            val fake = process as? FakeProcess
            if (fake != null && fake.forceKillCalls.get() > 0) {
                WaitOutcome.EXITED
            } else {
                waitStarted.countDown()
                try {
                    releaseWait.await(10, TimeUnit.SECONDS)
                    WaitOutcome.EXITED
                } catch (interrupted: InterruptedException) {
                    WaitOutcome.INTERRUPTED
                }
            }
        }
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = wait,
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            // The second replace originates on the real EDT; it must return without waiting
            // even though the background swap is blocked in the wait policy.
            val edtDone = CountDownLatch(1)
            val onEdt = AtomicBoolean()
            SwingUtilities.invokeLater {
                onEdt.set(SwingUtilities.isEventDispatchThread())
                supervisor.replace(listOf("second"))
                edtDone.countDown()
            }
            assertTrue(edtDone.await(2, TimeUnit.SECONDS), "replace must not block the EDT")
            assertTrue(onEdt.get(), "the call must run on the EDT")
            assertEquals(1, launcher.launchCount.get(),
                "the second launch must still be pending while the swap is blocked")
            releaseWait.countDown()
            assertTrue(launcher.allLaunched.await(5, TimeUnit.SECONDS))
            assertEquals(2, launcher.launchCount.get())
            assertEquals(listOf(fake1, fake2), launcher.launched)
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "replaced child exit observed")
            assertEquals(listOf(ExitCause.TERMINATED), exits)
            disposeAndAssertNoThreads(supervisor, executor)
        } finally {
            releaseWait.countDown()
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun disposeTerminatesHungChildAndLeavesNoThreads() {
        val fake = FakeProcess(gracefulExit = false)
        val launcher = FakeLauncher(listOf(fake))
        val statusLatch = CountDownLatch(1)
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = defaultFakePolicy(),
            onStatus = { statusLatch.countDown() },
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("preview"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            fake.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":4}\n")
            assertTrue(statusLatch.await(5, TimeUnit.SECONDS), "child must be demonstrably live")
            supervisor.dispose()
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "exit must fire on disposal")
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(listOf(ExitCause.TERMINATED), exits)
            assertEquals(1, fake.destroyCalls.get())
            assertEquals(1, fake.forceKillCalls.get(), "hung child needs the force-kill")
            assertFalse(fake.alive)
            assertNoPreviewThreads()
            // A disposed owner ignores later replaces without launching anything.

            supervisor.replace(listOf("ignored"))
            assertEquals(1, launcher.launchCount.get(), "no launch after disposal")
        } finally {
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun replacementHandoffForwardsOnlyTheNewChild() {
        val fake1 = FakeProcess()
        val fake2 = FakeProcess()
        val launcher = FakeLauncher(listOf(fake1, fake2), expectedLaunches = 2)
        val firstStatus = CountDownLatch(1)
        val secondStatus = CountDownLatch(1)
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = defaultFakePolicy(),
            onStatus = {
                if (it.nodes == 1) firstStatus.countDown()
                if (it.nodes == 2) secondStatus.countDown()
            },
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            fake1.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":1}\n")
            assertTrue(firstStatus.await(5, TimeUnit.SECONDS), "first child forwards status")
            supervisor.replace(listOf("second"))
            assertTrue(launcher.allLaunched.await(5, TimeUnit.SECONDS))
            assertEquals(1, fake1.destroyCalls.get())
            assertEquals(0, fake1.forceKillCalls.get(), "graceful child needs no force-kill")
            fake2.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":2}\n")
            assertTrue(secondStatus.await(5, TimeUnit.SECONDS), "second child forwards status")
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "replaced child exit observed")
            assertEquals(listOf(ExitCause.TERMINATED), exits,
                "the replaced child is reported as terminated")
            disposeAndAssertNoThreads(supervisor, executor)
        } finally {
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun defaultWaitPolicyTimesOutThenConfirmsForceKill() {
        val process = ProcessBuilder("sh", "-c", "trap '' TERM; while :; do sleep 1; done").start()
        try {
            val now = System::nanoTime
            val wait = PreviewProcessSupervisor.defaultWait(now)
            assertEquals(
                WaitOutcome.TIMED_OUT,
                wait(process, now() + 300_000_000L),
                "a live process must time out of the bounded wait")
            process.destroy()
            assertEquals(
                WaitOutcome.TIMED_OUT,
                wait(process, now() + 300_000_000L),
                "an ignored SIGTERM still times out")
            process.destroyForcibly()
            assertEquals(
                WaitOutcome.EXITED,
                wait(process, now() + 5_000_000_000L),
                "the final wait must observe the kill")
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun replacementIsGatedOnConfirmedExit() {
        val fake1 = FakeProcess(gracefulExit = false, neverExits = true)
        val fake2 = FakeProcess()
        val launcher = FakeLauncher(listOf(fake1, fake2), expectedLaunches = 2)
        val stderr = CopyOnWriteArrayList<String>()
        val failureLatch = CountDownLatch(1)
        val exits = CopyOnWriteArrayList<ExitCause>()
        val exitLatch = CountDownLatch(1)
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = defaultFakePolicy(),
            onStderr = {
                stderr += it
                failureLatch.countDown()
            },
            onExit = {
                exits += it
                exitLatch.countDown()
            },
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            fake1.writeStdout("markup-status: {\"schemaVersion\":2,\"ok\":true,\"nodes\":1}\n")
            supervisor.replace(listOf("second"))
            assertTrue(failureLatch.await(5, TimeUnit.SECONDS),
                "a child that survives SIGKILL must be reported")
            assertEquals(1, launcher.launchCount.get(),
                "no replacement may launch without a confirmed exit")
            assertTrue(stderr.any { it.contains("failed to terminate") },
                "the failure must be reported: $stderr")
            assertEquals(2, fake1.forceKillCalls.get(), "bounded force-kill retry")
            // The child is still owned; once it finally dies on its own, a later replace
            // retries termination and succeeds.
            fake1.selfExit()
            assertTrue(exitLatch.await(5, TimeUnit.SECONDS), "self exit must release ownership")
            assertEquals(listOf(ExitCause.SELF), exits)
            supervisor.replace(listOf("third"))
            assertTrue(launcher.allLaunched.await(5, TimeUnit.SECONDS))
            assertEquals(2, launcher.launchCount.get())
            disposeAndAssertNoThreads(supervisor, executor)
        } finally {
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun queuedReplaceAfterDisposeNeverLaunches() {
        val fake1 = FakeProcess()
        val fake2 = FakeProcess()
        val launcher = FakeLauncher(listOf(fake1, fake2))
        val waitStarted = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
        val wait: WaitPolicy = { process, _ ->
            val fake = process as? FakeProcess
            if (fake != null && fake.forceKillCalls.get() > 0) {
                WaitOutcome.EXITED
            } else {
                waitStarted.countDown()
                try {
                    releaseWait.await(10, TimeUnit.SECONDS)
                    WaitOutcome.EXITED
                } catch (interrupted: InterruptedException) {
                    WaitOutcome.INTERRUPTED
                }
            }
        }
        val executor = newExecutor(AtomicReference())
        val supervisor = newSupervisor(
            launcher = launcher::launch,
            executor = executor,
            wait = wait,
        )
        try {
            supervisor.replace(listOf("first"))
            assertTrue(launcher.firstLaunch.await(5, TimeUnit.SECONDS))
            // A second replace is mid-flight (blocked terminating child #1) when disposal
            // happens, and a third replace is already queued behind it.
            supervisor.replace(listOf("second"))
            assertTrue(waitStarted.await(5, TimeUnit.SECONDS), "termination must be in flight")
            supervisor.replace(listOf("third"))
            supervisor.dispose()
            releaseWait.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertEquals(1, launcher.launchCount.get(),
                "no launch may happen once dispose has been called, even mid-flight")
            assertNoPreviewThreads()
        } finally {
            releaseWait.countDown()
            supervisor.dispose()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    private fun newSupervisor(
        launcher: (List<String>) -> Process,
        executor: ExecutorService,
        wait: WaitPolicy,
        onStatus: (MarkupStatusLine) -> Unit = {},
        onProse: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
        onExit: (ExitCause) -> Unit = {},
    ): PreviewProcessSupervisor = PreviewProcessSupervisor(
        launcher = launcher,
        executor = executor,
        nowNanos = System::nanoTime,
        waitUntilExited = wait,
        onStatus = onStatus,
        onProse = onProse,
        onStderr = onStderr,
        onExit = onExit,
    )

    private fun newExecutor(threadRef: AtomicReference<Thread>): ExecutorService =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "test-owner-lifecycle").apply {
                isDaemon = true
                threadRef.set(this)
            }
        }

    /** EXITED only when the child is actually dead — graceful, stubborn, or never-exiting. */
    private fun defaultFakePolicy(): WaitPolicy = { process, _ ->
        val fake = process as? FakeProcess
        if (fake != null && !fake.alive) WaitOutcome.EXITED else WaitOutcome.TIMED_OUT
    }

    private fun assertNoPreviewThreads() {
        val alive = Thread.getAllStackTraces().keys.filter {
            it.isAlive && it.name.startsWith("markup-preview-")
        }
        assertTrue(alive.isEmpty(), "leaked preview threads: ${alive.map { it.name }}")
    }

    /** Disposes and proves the executor and every drain thread are gone. */
    private fun disposeAndAssertNoThreads(
        supervisor: PreviewProcessSupervisor,
        executor: ExecutorService,
    ) {
        supervisor.dispose()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "executor must terminate")
        assertNoPreviewThreads()
    }

    /** Scripted child double: real pipes for the drains, recorded kill calls. */
    private class FakeProcess(
        private val gracefulExit: Boolean = true,
        private val neverExits: Boolean = false,
    ) : Process() {
        private val stdoutSink = PipedOutputStream()
        private val stderrSink = PipedOutputStream()
        // Readers are connected eagerly so writes never race the drain thread's connection.
        private val stdoutReader = PipedInputStream(stdoutSink, 1024)
        private val stderrReader = PipedInputStream(stderrSink, 1024)
        private val stdin = ByteArrayOutputStream()
        private val exit = CountDownLatch(1)
        @Volatile
        var alive: Boolean = true
            private set
        val destroyCalls = AtomicInteger()
        val forceKillCalls = AtomicInteger()
        var onDestroy: () -> Unit = {}
        var onForceKill: () -> Unit = {}

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutReader
        override fun getErrorStream(): InputStream = stderrReader

        fun writeStdout(line: String) {
            stdoutSink.write(line.toByteArray())
            stdoutSink.flush()
        }

        fun writeStderr(line: String) {
            stderrSink.write(line.toByteArray())
            stderrSink.flush()
        }

        /** The child exits on its own, exactly like a well-behaved preview. */
        fun selfExit() = die()

        override fun destroy() {
            destroyCalls.incrementAndGet()
            onDestroy()
            if (gracefulExit) die()
        }

        override fun destroyForcibly(): Process {
            forceKillCalls.incrementAndGet()
            onForceKill()
            if (!neverExits) die()
            return this
        }

        private fun die() {
            if (!alive) return
            alive = false
            runCatching { stdoutSink.close() }
            runCatching { stderrSink.close() }
            exit.countDown()
        }

        override fun isAlive(): Boolean = alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun waitFor(): Int {
            exit.await()
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
            exit.await(timeout, unit)
    }

    /** Returns a fresh [FakeProcess] per launch so tests can address each child by index. */
    private class FakeLauncher(
        private val fakes: List<FakeProcess>,
        expectedLaunches: Int = 1,
    ) {
        private val index = AtomicInteger()
        val launchCount = AtomicInteger()
        val launched = CopyOnWriteArrayList<FakeProcess>()
        val firstLaunch = CountDownLatch(1)
        val allLaunched = CountDownLatch(expectedLaunches)

        fun launch(command: List<String>): Process {
            val process = fakes[index.getAndIncrement().coerceAtMost(fakes.size - 1)]
            launched += process
            launchCount.incrementAndGet()
            firstLaunch.countDown()
            allLaunched.countDown()
            return process
        }
    }
}
