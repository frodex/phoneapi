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
    fun `T2 heartbeats keep coming with no frames`() {
        val beat = VideoHeartbeat().apply { open(0) }
        val first = beat.poll(0, lastFrameAt = 0)
        val mid = beat.poll(500, lastFrameAt = 0)
        val second = beat.poll(1000, lastFrameAt = 0)
        val third = beat.poll(2000, lastFrameAt = 0)
        assertEquals("""{"t":"alive","lastFrameMs":0}""", first)
        assertNull(mid)
        assertEquals("""{"t":"alive","lastFrameMs":1000}""", second)
        assertEquals("""{"t":"alive","lastFrameMs":2000}""", third)
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
