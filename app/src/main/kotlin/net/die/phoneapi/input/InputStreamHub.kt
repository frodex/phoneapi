package net.die.phoneapi.input

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson

/** One live-touch websocket. Tests install a fake; [InputStreamHub] is the real one. */
interface InputStreams {
    fun isBusy(): Boolean

    fun tryAcquire(): Boolean

    fun release()

    suspend fun serve(session: DefaultWebSocketServerSession)
}

/** The one live-touch socket. [bounds] must be the logical display, from logicalSize. */
class InputStreamHub(
    private val now: () -> Long,
    private val bounds: () -> Pair<Int, Int>,
    private val inject: suspend (StrokeEvent) -> Unit,
    private val slots: StreamSlots = StreamSlots(),
) : InputStreams {
    @Volatile private var active: LiveTouch? = null

    override fun isBusy(): Boolean = active?.isBusy() == true

    override fun tryAcquire(): Boolean = slots.tryAcquire()

    override fun release() {
        active = null
        slots.release()
    }

    override suspend fun serve(session: DefaultWebSocketServerSession) {
        val touch = LiveTouch(now, bounds, inject)
        active = touch
        val idle =
            session.launch {
                while (isActive) {
                    delay(250)
                    touch.onTime(now())
                }
            }
        val writer =
            session.launch {
                for (reply in touch.replies) {
                    session.send(Frame.Text(ApiJson.encodeToString(serializer<StreamReply>(), reply)))
                }
            }
        try {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                touch.offer(frame.readText())
                if (touch.overRate) {
                    session.close(
                        CloseReason(
                            CloseReason.Codes.VIOLATED_POLICY,
                            "More than 240 input messages in one second",
                        )
                    )
                    break
                }
            }
        } finally {
            idle.cancel()
            touch.onClosed()
            touch.join()
            touch.replies.close()
            writer.join()
            active = null
        }
    }
}
