package net.die.phoneapi.lease

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.ScreenCleanupOnRelease
import net.die.phoneapi.server.FakeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenLeaseTest {
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private val c = Holder.Session("c")
    private val person = Holder.Viewer(1)

    private var cleanups = 0
    private var failures = emptyList<String>()

    private fun TestScope.lease(scope: CoroutineScope = backgroundScope) =
        ScreenLease(
            scope = scope,
            cleanup = {
                cleanups++
                CleanupResult(failures = failures)
            },
            now = { testScheduler.currentTime },
        )

    @Test
    fun `session keeps the screen`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        assertTrue(lease.holds(a))
        val waiting = async { lease.withScreen(b) { "b ran" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        lease.release(a)
        assertEquals("b ran", waiting.await().value)
        assertEquals(1, cleanups)
        assertTrue(lease.holds(b))
    }

    @Test
    fun `between calls waits its turn`() = runTest {
        val lease = lease()
        val going = CompletableDeferred<Unit>()
        val call = async { lease.withScreen(a) { going.await() } }
        runCurrent()
        val between = async { lease.betweenCalls { "fixed" } }
        runCurrent()
        assertFalse(between.isCompleted)
        going.complete(Unit)
        call.await()
        assertEquals("fixed", between.await())
    }

    @Test
    fun `idle holder yields to waiter`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        // The grace counts from the holder's last screen call.
        val waiting = async { lease.withScreen(b) { "b ran" } }
        advanceTimeBy(ScreenLease.GRACE_MS - 1)
        assertFalse(waiting.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("b ran", waiting.await().value)
        assertEquals(1, cleanups)
        assertTrue(lease.holds(b))
        lease.release(b)
        val notes = lease.withScreen(a) {}.notes
        assertTrue(notes.any { it.contains("went to another client") }, notes.toString())
    }

    @Test
    fun `next holder hears of cleanup`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        val notes = lease.withScreen(b) {}.notes
        assertTrue(notes.single().contains("cleaned up after the last holder"), notes.toString())
    }

    @Test
    fun `long waits are noted`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val waiting = async { lease.withScreen(b) {} }
        advanceTimeBy(5_000)
        lease.release(a)
        runCurrent()
        val notes = waiting.await().notes
        assertTrue(notes.any { it.contains("waited 5 s") }, notes.toString())
    }

    @Test
    fun `active holder keeps screen`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val waiting = async { apiError { lease.withScreen(b) {} } }
        repeat(6) {
            advanceTimeBy(ScreenLease.GRACE_MS / 2)
            lease.withScreen(a) {}
        }
        assertEquals("screen_busy", waiting.await().error)
        assertTrue(lease.holds(a))
        assertEquals(0, cleanups)
    }

    @Test
    fun `no waiter, no yield`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        advanceTimeBy(10 * ScreenLease.GRACE_MS)
        assertTrue(lease.holds(a))
        assertEquals(0, cleanups)
    }

    @Test
    fun `wait ends in screen busy`() = runTest {
        val lease = lease()
        // A holder in a long call keeps the screen, however long it runs.
        val going = CompletableDeferred<Unit>()
        val holding = async { lease.withScreen(a) { going.await() } }
        advanceTimeBy(5_000)
        val waiting = async { apiError { lease.withScreen(b) {} } }
        advanceTimeBy(ScreenLease.WAIT_MS + 1)
        val error = waiting.await()
        assertEquals("screen_busy", error.error)
        assertTrue(error.message.orEmpty().contains("65 s"))
        going.complete(Unit)
        holding.await()
        lease.release(a)
        runCurrent()
        assertNull(lease.state.value.holder)
    }

    @Test
    fun `canceled waiter leaves queue`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val order = mutableListOf<String>()
        val second = async { lease.withScreen(b) { order += "b" } }
        runCurrent()
        val third = async { lease.withScreen(c) { order += "c" } }
        runCurrent()
        second.cancel()
        runCurrent()
        lease.release(a)
        third.await()
        assertEquals(listOf("c"), order)
        assertTrue(lease.holds(c))
    }

    @Test
    fun `failed cleanup reaches next`() = runTest {
        val lease = lease()
        failures = listOf("lock: no keyguard")
        lease.withScreen(a) {}
        lease.release(a)
        runCurrent()
        val first = lease.withScreen(b) {}
        assertEquals(1, first.notes.size)
        assertEquals(
            "Cleanup after the last holder did not finish: lock: no keyguard. " +
                "Check the screen before acting.",
            first.notes.single(),
        )
        assertEquals(emptyList<String>(), lease.withScreen(b) {}.notes)
    }

    @Test
    fun `viewer input takes free screen`() = runTest {
        val lease = lease()
        assertTrue(lease.viewerInput(person))
        assertTrue(lease.holds(person))
        lease.release(person)
        runCurrent()
        lease.withScreen(a) {}
        assertFalse(lease.viewerInput(person))
        assertTrue(lease.holds(a))
    }

    @Test
    fun `take over and hand back`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        val started = CompletableDeferred<Unit>()
        val call = async {
            apiError {
                lease.withScreen(a) {
                    started.complete(Unit)
                    delay(10_000)
                }
            }
        }
        started.await()
        advanceTimeBy(1_000)
        lease.takeOver(person)
        assertEquals("screen_taken", call.await().error)
        val again = apiError { lease.withScreen(a) {} }
        assertEquals("screen_taken", again.error)

        advanceTimeBy(30_000)
        lease.release(person)
        assertTrue(lease.holds(a))
        val back = lease.withScreen(a) { "ran" }
        assertEquals("ran", back.value)
        assertTrue(back.notes.single().contains("30 s"))
        assertEquals(0, cleanups)
    }

    @Test
    fun `hand back skips ended session`() = runTest {
        val lease = lease()
        lease.withScreen(a) {}
        lease.takeOver(person)
        lease.forget(a)
        lease.release(person)
        runCurrent()
        assertNull(lease.state.value.holder)
        assertEquals(1, cleanups)
    }

    @Test
    fun `waiters wait for cleanup`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val lease =
            ScreenLease(
                scope = backgroundScope,
                cleanup = {
                    gate.await()
                    CleanupResult()
                },
                now = { testScheduler.currentTime },
            )
        lease.withScreen(a) {}
        lease.release(a)
        val waiting = async { lease.withScreen(b) { "b" } }
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(lease.viewerInput(person))
        gate.complete(Unit)
        assertEquals("b", waiting.await().value)
    }

    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `policy transfer and notes`(mode: ScreenCleanupOnRelease) {
        runTest {
            for (idle in listOf(false, true)) {
                val api = FakeApi()
                val cleanup =
                    ScreenCleanup(
                        api.services.input,
                        api.services.power,
                        showPlaceholder = { api.cleanupActions += "placeholder" },
                        policy = { mode },
                    )
                val lease =
                    ScreenLease(backgroundScope, cleanup::run, now = { testScheduler.currentTime })
                lease.withScreen(a) {}
                val next = async { lease.withScreen(b) {} }
                runCurrent()
                if (idle) advanceTimeBy(ScreenLease.GRACE_MS + 1) else lease.release(a)
                runCurrent()
                val result = next.await()
                assertEquals(expectedActions(mode), api.cleanupActions)
                assertTrue(lease.holds(b))
                assertTrue(
                    result.notes.contains(expectedCleanupNote(mode)),
                    result.notes.toString(),
                )
                if (mode != ScreenCleanupOnRelease.HOME_LOCK) {
                    assertFalse(lease.takeNotes(a).any { it.contains("home and locked") })
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `cleanup exceptions`(mode: ScreenCleanupOnRelease) {
        runTest {
            val api = FakeApi()
            val cleanup =
                ScreenCleanup(
                    api.services.input,
                    api.services.power,
                    showPlaceholder = { api.cleanupActions += "placeholder" },
                    policy = { mode },
                )
            var calls = 0
            val lease =
                ScreenLease(
                    backgroundScope,
                    {
                        calls++
                        cleanup.run()
                    },
                    now = { testScheduler.currentTime },
                )
            lease.release(a)
            lease.withScreen(a) {}
            val waiting = async { lease.withScreen(b) {} }
            runCurrent()
            waiting.cancel()
            runCurrent()
            lease.takeOver(person)
            lease.release(person)
            advanceTimeBy(ScreenLease.GRACE_MS * 2)
            runCurrent()
            assertTrue(lease.holds(a))
            assertEquals(0, calls)
            assertEquals(emptyList<String>(), api.cleanupActions)
        }
    }

    private suspend fun apiError(block: suspend () -> Unit): ApiException =
        try {
            block()
            error("expected an ApiException")
        } catch (e: ApiException) {
            e
        }
}
