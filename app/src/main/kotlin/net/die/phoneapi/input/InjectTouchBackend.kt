package net.die.phoneapi.input

import android.os.RemoteException
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helper.AxisRange
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.TimedPoint

/**
 * Touches through the helper's `InputManager.injectInputEvent`, stamped with the real touchscreen
 * so they are not accessibility gestures.
 */
class InjectTouchBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) {
    val name = "inject"

    private val gestures = Mutex()
    private val screenLock = Mutex()

    /** Dropped when [helper] hands out a different binder proxy. */
    @Volatile private var screenProxy: IHelper? = null
    @Volatile private var screen: TouchscreenInfo? = null

    suspend fun perform(pointers: List<List<TimedPoint>>): Boolean = gestures.withLock {
        val proxy = helper.require()
        val device =
            try {
                touchscreen(proxy)
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            }
        val samples =
            try {
                touchTimeline(pointers)
            } catch (e: IllegalArgumentException) {
                throw ApiException.badRequest(e.message ?: "Invalid gesture", e)
            }
        val events = motionEvents(samples, device, SystemClock.uptimeMillis())
        try {
            withContext(io) {
                var ok = true
                for (event in events) {
                    val wait = event.eventTime - SystemClock.uptimeMillis()
                    if (wait > 0) delay(wait)
                    ok = proxy.injectMotionEvent(event, WAIT_FOR_FINISH) && ok
                }
                ok
            }
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        } finally {
            events.forEach { it.recycle() }
        }
    }

    /** One live pointer change. The caller owns the stroke clock ([StrokeEvent.downTime]). */
    suspend fun injectOne(event: StrokeEvent): Boolean {
        val proxy = helper.require()
        val device =
            try {
                touchscreen(proxy)
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            }
        val phase =
            when (event.action) {
                StrokeAction.DOWN -> TouchPhase.DOWN
                StrokeAction.MOVE -> TouchPhase.MOVE
                StrokeAction.UP, StrokeAction.CANCEL -> TouchPhase.UP
            }
        val action =
            when (event.action) {
                StrokeAction.DOWN -> MotionEvent.ACTION_DOWN
                StrokeAction.MOVE -> MotionEvent.ACTION_MOVE
                StrokeAction.UP -> MotionEvent.ACTION_UP
                StrokeAction.CANCEL -> MotionEvent.ACTION_CANCEL
            }
        val motion =
            obtain(
                event.downTime,
                event.eventTime,
                action,
                listOf(TouchSample(0, event.x, event.y, 0, phase)),
                device,
            )
        try {
            return withContext(io) { proxy.injectMotionEvent(motion, WAIT_FOR_FINISH) }
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        } finally {
            motion.recycle()
        }
    }

    private suspend fun touchscreen(proxy: IHelper): TouchscreenInfo {
        if (screenProxy === proxy) {
            screen?.let {
                return it
            }
        }
        return screenLock.withLock {
            if (screenProxy === proxy) {
                screen?.let {
                    return it
                }
            }
            val parsed = withContext(io) { proxy.touchscreenInfo() }
            screenProxy = proxy
            screen = parsed
            parsed
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH. */
        const val WAIT_FOR_FINISH = 2
    }
}

private fun motionEvents(
    samples: List<TouchSample>,
    screen: TouchscreenInfo,
    start: Long,
): List<MotionEvent> {
    val active = LinkedHashMap<Int, TouchSample>()
    var downTime = start
    val events = ArrayList<MotionEvent>(samples.size)
    for (sample in samples) {
        val eventTime = start + sample.tMs
        when (sample.phase) {
            TouchPhase.DOWN -> {
                if (active.isEmpty()) downTime = eventTime
                active[sample.pointer] = sample
                val action =
                    if (active.size == 1) {
                        MotionEvent.ACTION_DOWN
                    } else {
                        pointerAction(MotionEvent.ACTION_POINTER_DOWN, active, sample.pointer)
                    }
                events += obtain(downTime, eventTime, action, active.values.toList(), screen)
            }
            TouchPhase.MOVE -> {
                active[sample.pointer] = sample
                events +=
                    obtain(
                        downTime,
                        eventTime,
                        MotionEvent.ACTION_MOVE,
                        active.values.toList(),
                        screen,
                    )
            }
            TouchPhase.UP -> {
                active[sample.pointer] = sample
                val action =
                    if (active.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        pointerAction(MotionEvent.ACTION_POINTER_UP, active, sample.pointer)
                    }
                events += obtain(downTime, eventTime, action, active.values.toList(), screen)
                active.remove(sample.pointer)
            }
        }
    }
    return events
}

private fun pointerAction(base: Int, active: Map<Int, TouchSample>, pointer: Int): Int {
    val index = active.keys.indexOf(pointer).coerceAtLeast(0)
    return base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
}

@Suppress("DEPRECATION")
private fun obtain(
    downTime: Long,
    eventTime: Long,
    action: Int,
    pointers: List<TouchSample>,
    screen: TouchscreenInfo,
): MotionEvent {
    val properties =
        Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].pointer
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
    val coords =
        Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                val sample = pointers[index]
                x = sample.x
                y = sample.y
                pressure = along(screen.pressure, PRESSURE_FRACTION)
                size = along(screen.size, SIZE_FRACTION)
                touchMajor = along(screen.touchMajor, SIZE_FRACTION)
                touchMinor = along(screen.touchMinor, SIZE_FRACTION)
                orientation = along(screen.orientation, CENTER_FRACTION)
            }
        }
    // A device's source mask can include bits the input verifier does not know. On API 36 an
    // unknown source aborts system_server, so a finger is always a plain touchscreen event.
    val source =
        screen.source.takeIf { it == InputDevice.SOURCE_TOUCHSCREEN }
            ?: InputDevice.SOURCE_TOUCHSCREEN
    return MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        pointers.size,
        properties,
        coords,
        0,
        0,
        1f,
        1f,
        screen.deviceId,
        0,
        source,
        0,
    )
}

/**
 * A point [fraction] of the way from min to max, so a finger is not the exact center of the range.
 */
private fun along(range: AxisRange, fraction: Float): Float {
    if (range.max <= range.min) return range.min
    return range.min + (range.max - range.min) * fraction
}

private const val PRESSURE_FRACTION = 0.55f
private const val SIZE_FRACTION = 0.08f
private const val CENTER_FRACTION = 0.5f
