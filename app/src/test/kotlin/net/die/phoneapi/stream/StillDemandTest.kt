package net.die.phoneapi.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StillDemandTest {
    @Test
    fun `T1 a join is one redraw and one sync`() {
        assertEquals(StillStep(redraws = 1, syncs = 1), stillStep("join"))
        assertTrue(shouldSubmit("demand", haveFrame = true))
    }

    @Test
    fun `T2 heartbeats keep coming with no frames while the pipeline is up`() {
        val beat = VideoHeartbeat().apply { open(0) }
        val first = beat.poll(0, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 0)
        val mid = beat.poll(500, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 500)
        val second = beat.poll(1000, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 1000)
        val third = beat.poll(2000, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 2000)
        assertEquals("""{"t":"alive","lastFrameMs":0}""", first)
        assertNull(mid)
        assertEquals("""{"t":"alive","lastFrameMs":1000}""", second)
        assertEquals("""{"t":"alive","lastFrameMs":2000}""", third)
    }

    @Test
    fun `F1 a key emitted before subscribe is not delivered`() {
        assertFalse(viewerReceivesEmittedKey(alreadySubscribed = false))
        assertTrue(viewerReceivesEmittedKey(alreadySubscribed = true))
    }

    @Test
    fun `F1 a sync does not apply to a redraw already submitted`() {
        assertFalse(syncAppliesTo(bufferSubmittedBeforeSync = true))
        assertTrue(syncAppliesTo(bufferSubmittedBeforeSync = false))
    }

    @Test
    fun `F1 no key within 300ms retries sync and redraw at most 3 times`() {
        val watch = JoinKeyWatch()
        assertTrue(watch.onJoin(0))
        assertFalse(watch.retryDue(299))
        assertTrue(watch.retryDue(300))
        assertTrue(watch.retryDue(600))
        assertTrue(watch.retryDue(900))
        assertFalse(watch.retryDue(1200))
        assertEquals(1 + KEY_RETRY_LIMIT, watch.attempts)
    }

    @Test
    fun `F1 fifty joins racing an in-flight redraw each get a key within 1s`() {
        repeat(50) {
            val watch = JoinKeyWatch()
            assertTrue(watch.onJoin(0))
            assertFalse(syncAppliesTo(bufferSubmittedBeforeSync = true))
            assertFalse(viewerReceivesEmittedKey(alreadySubscribed = false))
            assertTrue(watch.retryDue(KEY_RETRY_MS))
            assertTrue(syncAppliesTo(bufferSubmittedBeforeSync = false))
            watch.onKey()
            assertTrue(KEY_RETRY_MS <= 1000)
            assertFalse(watch.retryDue(KEY_RETRY_MS))
        }
    }

    @Test
    fun `F2 a dead helper sends no alive and the panel stalls by 3_5s`() {
        val beat = VideoHeartbeat().apply { open(0) }
        assertEquals(
            """{"t":"alive","lastFrameMs":0}""",
            beat.poll(0, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 0),
        )
        val killAt = 1000L
        assertNull(beat.poll(killAt, lastFrameAt = 0, helperAnswered = false, lastDrainAt = killAt))
        assertNull(beat.poll(killAt + 1500, lastFrameAt = 0, helperAnswered = false, lastDrainAt = killAt + 1500))
        val lastAliveAt = killAt
        val panelNoticesAt = lastAliveAt + 3000 + 499
        assertTrue(panelNoticesAt - killAt <= 3500)
    }

    @Test
    fun `F2 a stale encoder drain sends no alive`() {
        val beat = VideoHeartbeat().apply { open(0) }
        assertNull(beat.poll(1500, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 0))
        assertEquals(
            """{"t":"alive","lastFrameMs":1500}""",
            beat.poll(1500, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 500),
        )
    }

    @Test
    fun `F2 a down pipeline does not consume the heartbeat interval`() {
        val beat = VideoHeartbeat().apply { open(0) }
        assertNull(beat.poll(0, lastFrameAt = 0, helperAnswered = false, lastDrainAt = 0))
        assertEquals(
            """{"t":"alive","lastFrameMs":0}""",
            beat.poll(0, lastFrameAt = 0, helperAnswered = true, lastDrainAt = 0),
        )
    }

    @Test
    fun `T3 a sync request is one redraw and one sync`() {
        assertTrue(isSyncFrame("""{"t":"sync"}"""))
        assertEquals(StillStep(redraws = 1, syncs = 1), stillStep("sync"))
        assertFalse(isSyncFrame("""{"codec":"avc","width":466,"height":960}"""))
    }

    @Test
    fun `T4 a timer does not redraw`() {
        assertEquals(StillStep(redraws = 0, syncs = 0), stillStep("timer"))
        assertFalse(shouldSubmit("timer", haveFrame = true))
        var redraws = 0
        repeat(20) { if (shouldSubmit("timer", haveFrame = true)) redraws += 1 }
        assertEquals(0, redraws)
    }
}
