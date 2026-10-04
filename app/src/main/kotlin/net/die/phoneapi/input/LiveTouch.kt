package net.die.phoneapi.input

import io.ktor.websocket.CloseReason
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.ApiException

/** One injected pointer change. [downTime] is shared by the whole stroke. */
data class StrokeEvent(
    val action: StrokeAction,
    val x: Float,
    val y: Float,
    val downTime: Long,
    val eventTime: Long,
)

enum class StrokeAction {
    DOWN,
    MOVE,
    UP,
    CANCEL,
}

/** A text frame back to the stream client. [seq] is the client's, or omitted for a synthetic cancel. */
@Serializable
data class StreamReply(
    val t: String,
    val seq: Long,
    val msg: String? = null,
    val injectedAtMs: Long? = null,
)

/**
 * One finger, injected as each message arrives. Moves that pile up behind an injection in flight
 * collapse to the newest. A stroke that is abandoned is cancelled, so the finger is not left down.
 *
 * [bounds] is the logical display (a resolution override), not [DeviceInfoProvider]'s physical mode.
 */
class LiveTouch(
    private val now: () -> Long,
    private val bounds: () -> Pair<Int, Int>,
    private val inject: suspend (StrokeEvent) -> Unit,
) {
    val replies = Channel<StreamReply>(Channel.UNLIMITED)

    @Volatile var overRate: Boolean = false
        private set

    @Volatile private var stroke = false
    private var ending = false
    private var downTime = 0L
    private var lastEvent = 0L
    private var x = 0f
    private var y = 0f
    private var lastMsg = 0L
    private var windowStart = 0L
    private var windowCount = 0

    private val state = Mutex()
    private val pumpLock = Mutex()
    private var pumping = false
    private var coalesced: Item? = null

    fun isBusy(): Boolean = stroke

    fun busyException(): ApiException? = if (stroke) streamBusyException() else null

    suspend fun offer(text: String) {
        val item = state.withLock { accept(text) } ?: return
        submit(item)
    }

    suspend fun onClosed() {
        val item =
            state.withLock {
                if (!stroke || ending) return
                ending = true
                item(StrokeAction.CANCEL, x, y, SYNTHETIC_SEQ)
            }
        submit(item)
    }

    /** [t] is the same clock as [now]. Idle for [IDLE_MS] during a stroke injects cancel. */
    suspend fun onTime(t: Long) {
        val idle = state.withLock { stroke && !ending && lastMsg != 0L && t - lastMsg >= IDLE_MS }
        if (idle) onClosed()
    }

    suspend fun join() {
        while (pumpLock.withLock { pumping }) {
            kotlinx.coroutines.yield()
        }
    }

    private fun accept(text: String): Item? {
        if (overRate) return null
        val t = now()
        if (windowStart == 0L || t - windowStart >= 1_000) {
            windowStart = t
            windowCount = 0
        }
        windowCount++
        if (windowCount > MAX_PER_SECOND) {
            overRate = true
            return null
        }
        val msg =
            try {
                ApiJson.decodeFromString(InMsg.serializer(), text)
            } catch (_: Exception) {
                replies.trySend(StreamReply(t = "error", seq = 0, msg = "bad message"))
                return null
            }
        val seq = msg.seq
        if (msg.t == null || seq == null) {
            replies.trySend(StreamReply(t = "error", seq = seq ?: 0, msg = "bad message"))
            return null
        }
        when (msg.t) {
            "down" -> {
                if (stroke || ending) {
                    replies.trySend(StreamReply(t = "error", seq = seq, msg = "stroke already down"))
                    return null
                }
                if (msg.x == null || msg.y == null) {
                    replies.trySend(StreamReply(t = "error", seq = seq, msg = "down needs x and y"))
                    return null
                }
                val (cx, cy) = clamp(msg.x, msg.y)
                stroke = true
                ending = false
                x = cx
                y = cy
                lastMsg = t
                return item(StrokeAction.DOWN, cx, cy, seq)
            }
            "move", "up" -> {
                if (!stroke || ending) {
                    replies.trySend(StreamReply(t = "error", seq = seq, msg = "no stroke"))
                    return null
                }
                if (msg.x == null || msg.y == null) {
                    replies.trySend(StreamReply(t = "error", seq = seq, msg = "${msg.t} needs x and y"))
                    return null
                }
                val (cx, cy) = clamp(msg.x, msg.y)
                x = cx
                y = cy
                lastMsg = t
                if (msg.t == "up") ending = true
                val action = if (msg.t == "up") StrokeAction.UP else StrokeAction.MOVE
                return item(action, cx, cy, seq)
            }
            "cancel" -> {
                if (!stroke || ending) {
                    replies.trySend(StreamReply(t = "error", seq = seq, msg = "no stroke"))
                    return null
                }
                ending = true
                lastMsg = t
                return item(StrokeAction.CANCEL, x, y, seq)
            }
            else -> {
                replies.trySend(StreamReply(t = "error", seq = seq, msg = "unknown t"))
                return null
            }
        }
    }

    private suspend fun submit(item: Item) {
        val inline =
            pumpLock.withLock {
                if (!pumping) {
                    pumping = true
                    true
                } else {
                    val previous = coalesced
                    coalesced =
                        when {
                            item.action == StrokeAction.MOVE &&
                                (previous == null || previous.action == StrokeAction.MOVE) -> item
                            item.action == StrokeAction.MOVE -> previous
                            else -> item
                        }
                    false
                }
            }
        if (inline) drain(item)
    }

    private suspend fun drain(first: Item) {
        var failed = false
        try {
            var current: Item? = first
            while (current != null) {
                val item = current
                val eventTime = stamp()
                if (item.action == StrokeAction.DOWN) downTime = eventTime
                try {
                    inject(StrokeEvent(item.action, item.x, item.y, downTime, eventTime))
                } catch (e: Throwable) {
                    // helperDropped leaves this function via finally, which clears the pump.
                    failed = true
                    throw e
                }
                if (item.action == StrokeAction.UP || item.action == StrokeAction.CANCEL) {
                    state.withLock {
                        stroke = false
                        ending = false
                    }
                }
                if (item.seq >= 0) {
                    replies.trySend(StreamReply(t = "ack", seq = item.seq, injectedAtMs = now()))
                }
                current =
                    pumpLock.withLock {
                        val next = coalesced
                        coalesced = null
                        if (next == null) pumping = false
                        next
                    }
            }
        } finally {
            if (failed) {
                // Leave pumping clear before the exception escapes, so join() does not spin
                // and serve can return to release the slot.
                pumpLock.withLock {
                    pumping = false
                    coalesced = null
                }
                val point =
                    state.withLock {
                        val down = stroke
                        stroke = false
                        ending = false
                        if (down) x to y else null
                    }
                if (point != null) {
                    try {
                        withContext(NonCancellable) {
                            val eventTime = stamp()
                            inject(
                                StrokeEvent(
                                    StrokeAction.CANCEL,
                                    point.first,
                                    point.second,
                                    downTime,
                                    eventTime,
                                )
                            )
                        }
                    } catch (_: Throwable) {
                        // The helper is already dead. The stroke is cleared either way.
                    }
                }
            }
        }
    }

    private fun stamp(): Long {
        var t = now()
        if (t <= lastEvent) t = lastEvent + 1
        lastEvent = t
        return t
    }

    private fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val (w, h) = bounds()
        val maxX = (w - 1).coerceAtLeast(0).toFloat()
        val maxY = (h - 1).coerceAtLeast(0).toFloat()
        return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
    }

    private fun item(action: StrokeAction, x: Float, y: Float, seq: Long) = Item(action, x, y, seq)

    private data class Item(val action: StrokeAction, val x: Float, val y: Float, val seq: Long)

    @Serializable
    private data class InMsg(val t: String? = null, val x: Float? = null, val y: Float? = null, val seq: Long? = null)

    private companion object {
        const val MAX_PER_SECOND = 240
        const val SYNTHETIC_SEQ = -1L
    }
}

/** How long a silent stroke may stay down before it is cancelled. */
const val IDLE_MS = 5_000L

fun streamBusyException(): ApiException =
    ApiException(409, "busy", "An input stream stroke is down")

/** One input websocket at a time. A second is [SECOND_STREAM_CLOSE] (1013). */
class StreamSlots {
    private val open = AtomicBoolean(false)

    fun tryAcquire(): Boolean = open.compareAndSet(false, true)

    fun release() {
        open.set(false)
    }
}

val SECOND_STREAM_CLOSE =
    CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "Another input stream is open")
