package net.die.phoneapi.lease

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // The late check runs on a real dispatcher here.
class ForegroundWatchTest {
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")
    private val scope = CoroutineScope(Dispatchers.Default)
    private val lease = ScreenLease(scope, cleanup = { CleanupResult() })
    @Volatile private var top = "com.launcher"
    private val reported = mutableListOf<String>()
    private val watch =
        ForegroundWatch(
            lease,
            scope,
            top = { top },
            bringBack = { top = it },
            report = { holder, pkg -> synchronized(reported) { reported += "${holder.id}:$pkg" } },
            lateMs = LATE_MS,
        )

    @Test
    fun `holder may launch`() = runBlocking {
        lease.withScreen(a) {}
        watch.watch(a) { top = "com.maps" }
        assertEquals(0, watch.count(a))
    }

    @Test
    fun `free screen goes to culprit`() = runBlocking {
        assertEquals("took_screen", error { watch.watch(b) { top = "com.maps" } })
        assertTrue(lease.holds(b))
        assertEquals("com.maps", top)
        assertEquals(1, watch.count(b))
        assertEquals(listOf("b:com.maps"), reported)
    }

    @Test
    fun `holder's app comes back`() = runBlocking {
        lease.withScreen(a) {}
        top = "com.app"
        assertEquals("took_screen", error { watch.watch(b) { top = "com.maps" } })
        assertEquals("com.app", top)
        assertTrue(lease.holds(a))
        val notes = lease.withScreen(a) {}.notes
        assertTrue(notes.single().contains("com.maps came forward briefly"))
    }

    @Test
    fun `screen calls are not blamed`() = runBlocking {
        lease.withScreen(a) {}
        val going = CompletableDeferred<Unit>()
        val call = async {
            lease.withScreen(a) {
                top = "com.maps"
                going.await()
            }
        }
        watch.watch(b) { delay(SETTLE_MS) }
        going.complete(Unit)
        call.await()
        assertEquals(0, watch.count(b))
    }

    @Test
    fun `late launches are noted`() = runBlocking {
        watch.watch(b) {}
        top = "com.maps"
        withTimeout(WAIT_MS) { while (watch.count(b) == 0) delay(SETTLE_MS) }
        assertTrue(lease.holds(b))
        val notes = lease.withScreen(b) {}.notes
        assertTrue(notes.single().startsWith("took_screen: com.maps came forward just after"))
    }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    private companion object {
        const val LATE_MS = 100L
        const val SETTLE_MS = 20L
        const val WAIT_MS = 2_000L
    }
}
