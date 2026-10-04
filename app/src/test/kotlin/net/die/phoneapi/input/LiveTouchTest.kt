package net.die.phoneapi.input

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Live touch: one pointer, injected as it arrives. Spec: streaming-input S1–S8. */
class LiveTouchTest {
    @Test
    fun `S1 down move move up shares a downTime and rising eventTime`() = runBlocking {
        val events = mutableListOf<StrokeEvent>()
        var clock = 1_000L
        val live = liveTouch(now = { clock }, inject = { events += it; clock += 5 })
        live.offer("""{"t":"down","x":10,"y":20,"seq":1}""")
        live.offer("""{"t":"move","x":11,"y":21,"seq":2}""")
        live.offer("""{"t":"move","x":12,"y":22,"seq":3}""")
        live.offer("""{"t":"up","x":13,"y":23,"seq":4}""")
        live.join()
        assertEquals(listOf(StrokeAction.DOWN, StrokeAction.MOVE, StrokeAction.MOVE, StrokeAction.UP), events.map { it.action })
        assertEquals(1, events.map { it.downTime }.toSet().size)
        assertTrue(events.zipWithNext().all { (a, b) -> b.eventTime > a.eventTime })
    }

    @Test
    fun `S2 moves waiting on an injection collapse to the newest`() = runBlocking {
        val started = mutableListOf<StrokeEvent>()
        val release = CompletableDeferred<Unit>()
        val live =
            liveTouch(
                inject = { event ->
                    started += event
                    if (event.action == StrokeAction.DOWN) release.await()
                }
            )
        coroutineScope {
            val down = launch { live.offer("""{"t":"down","x":1,"y":2,"seq":1}""") }
            while (started.isEmpty()) yield()
            live.offer("""{"t":"move","x":10,"y":2,"seq":2}""")
            live.offer("""{"t":"move","x":30,"y":2,"seq":3}""")
            release.complete(Unit)
            while (started.size < 2) yield()
            down.join()
            live.offer("""{"t":"up","x":30,"y":2,"seq":4}""")
            live.join()
        }
        assertEquals(
            listOf(StrokeAction.DOWN, StrokeAction.MOVE, StrokeAction.UP),
            started.map { it.action },
        )
        assertEquals(30f, started[1].x)
    }

    @Test
    fun `S3 closing mid-stroke injects cancel`() = runBlocking {
        val events = mutableListOf<StrokeEvent>()
        val live = liveTouch(inject = { events += it })
        live.offer("""{"t":"down","x":4,"y":5,"seq":1}""")
        live.join()
        live.onClosed()
        live.join()
        assertEquals(StrokeAction.CANCEL, events.last().action)
        assertEquals(4f, events.last().x)
        assertEquals(events.first().downTime, events.last().downTime)
    }

    @Test
    fun `S4 five seconds without a message cancels a stroke`() = runBlocking {
        val events = mutableListOf<StrokeEvent>()
        var clock = 10_000L
        val live = liveTouch(now = { clock }, inject = { events += it })
        live.offer("""{"t":"down","x":8,"y":9,"seq":1}""")
        live.join()
        live.onTime(clock + 4_999)
        live.join()
        assertEquals(listOf(StrokeAction.DOWN), events.map { it.action })
        live.onTime(clock + 5_000)
        live.join()
        assertEquals(StrokeAction.CANCEL, events.last().action)
    }

    @Test
    fun `S5 a second stream is refused with 1013`() {
        val slots = StreamSlots()
        assertTrue(slots.tryAcquire())
        assertEquals(false, slots.tryAcquire())
        assertEquals(1013.toShort(), SECOND_STREAM_CLOSE.code)
        slots.release()
        assertTrue(slots.tryAcquire())
    }

    @Test
    fun `S6 a finished gesture is refused while a stroke is down`() = runBlocking {
        val live = liveTouch()
        assertEquals(null, live.busyException())
        live.offer("""{"t":"down","x":1,"y":1,"seq":1}""")
        live.join()
        assertEquals(409, live.busyException()?.status)
        assertEquals("busy", live.busyException()?.error)
        live.offer("""{"t":"up","x":1,"y":1,"seq":2}""")
        live.join()
        assertEquals(null, live.busyException())
    }

    @Test
    fun `S7 a move before down injects nothing`() = runBlocking {
        val events = mutableListOf<StrokeEvent>()
        val live = liveTouch(inject = { events += it })
        live.offer("""{"t":"move","x":3,"y":4,"seq":9}""")
        live.join()
        assertTrue(events.isEmpty())
        val reply = live.replies.receive()
        assertEquals("error", reply.t)
        assertEquals(9L, reply.seq)
    }

    @Test
    fun `S8 points clamp to the logical display not the physical mode`() = runBlocking {
        val events = mutableListOf<StrokeEvent>()
        val live = liveTouch(bounds = { 1080 to 2220 }, inject = { events += it })
        live.offer("""{"t":"down","x":1200,"y":-4,"seq":1}""")
        live.join()
        assertEquals(1079f, events.single().x)
        assertEquals(0f, events.single().y)
    }

    @Test
    fun `more than 240 messages in one second closes the stream`() = runBlocking {
        val events = mutableListOf<StrokeEvent>()
        val live = liveTouch(inject = { events += it })
        live.offer("""{"t":"down","x":1,"y":1,"seq":1}""")
        repeat(239) { i -> live.offer("""{"t":"move","x":${i + 2},"y":1,"seq":${i + 2}}""") }
        assertEquals(false, live.overRate)
        live.offer("""{"t":"move","x":5000,"y":1,"seq":241}""")
        assertEquals(true, live.overRate)
        live.join()
        assertEquals(240, events.size)
        assertTrue(events.none { it.x == 1079f })
    }

}

private fun liveTouch(
    now: () -> Long = { 1_000L },
    bounds: () -> Pair<Int, Int> = { 1080 to 2220 },
    inject: suspend (StrokeEvent) -> Unit = {},
): LiveTouch = LiveTouch(now, bounds, inject)
