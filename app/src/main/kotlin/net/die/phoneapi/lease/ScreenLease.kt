package net.die.phoneapi.lease

import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.ApiException

/** Who can hold the screen. See docs/sharing.md. */
sealed interface Holder {
    /** A session, from MCP or REST, keyed by the `Mcp-Session-Id` PhoneAPI gave it. */
    data class Session(val id: String) : Holder

    /** A person's input socket in `/viewer`. */
    data class Viewer(val id: Long) : Holder
}

/** Who has the screen now, for the viewer page and the admin routes. */
data class LeaseState(
    val holder: Holder? = null,
    val sinceMs: Long = 0,
    /** The session a viewer took the screen from, which gets it back. */
    val takenFrom: Holder.Session? = null,
)

/** A screen call's result, with notes the holder should see before it acts. */
data class Screened<T>(val value: T, val notes: List<String>)

/**
 * The one exclusive screen lease. A holder keeps it until [release]. Others wait first come, first
 * served for up to [waitMs], then fail with `screen_busy`. Releasing runs [cleanup] before the next
 * holder gets the screen, and the steps that failed reach that holder as notes.
 *
 * A viewer takes a free screen on input with [viewerInput], and an agent's only with [takeOver].
 * Taking over cancels the agent's screen call in progress and makes its next ones fail with
 * `screen_taken` until the viewer lets go, when the screen goes back to it without cleanup.
 *
 * A session that has made no screen call for [graceMs] while someone waits loses the screen to
 * them, with cleanup, and is told on its next screen call, which waits like any other. So a client
 * that keeps its session open between tasks cannot keep others off the screen.
 */
class ScreenLease(
    private val scope: CoroutineScope,
    private val cleanup: suspend () -> CleanupResult,
    private val now: () -> Long = System::currentTimeMillis,
    private val waitMs: Long = WAIT_MS,
    private val graceMs: Long = GRACE_MS,
) {
    private data class Waiter(val holder: Holder, val granted: CompletableDeferred<Unit>)

    private val mutex = Mutex()
    private val gate = Mutex()
    private val queue = ArrayDeque<Waiter>()
    private val notes = HashMap<Holder, MutableList<String>>()
    private val running = HashMap<Holder, MutableSet<Job>>()
    private val taken = HashSet<Job>()
    private val started = AtomicLong()
    private var cleaning = false
    private var pendingCleanup: String? = null
    /** When the holder last started or finished a screen call, or got the screen. */
    private var lastCallMs = 0L
    private var idleWatch: Job? = null
    private val _state = MutableStateFlow(LeaseState())

    val state: StateFlow<LeaseState> = _state.asStateFlow()

    /**
     * Runs [block] holding the screen for [holder], waiting for it first when someone else has it.
     * Throws `screen_busy` when the wait runs out and `screen_taken` when a viewer has taken the
     * screen from this holder.
     */
    suspend fun <T> withScreen(holder: Holder, block: suspend () -> T): Screened<T> {
        val asked = now()
        acquire(holder)
        val waited = now() - asked
        if (waited >= WAITED_NOTE_MS) {
            addNote(
                holder,
                "This call waited ${waited / MS_PER_S} s for the screen while another client " +
                    "used it.",
            )
        }
        val value = runTracked(holder, block)
        return Screened(value, takeNotes(holder))
    }

    /**
     * Runs [block] between screen calls: once none is running, and with none starting until it
     * returns. For putting Chrome back on the holder's tab without cutting into a gesture.
     */
    suspend fun <T> betweenCalls(block: suspend () -> T): T = gate.withLock {
        while (callRunning()) delay(BETWEEN_POLL_MS)
        block()
    }

    /** Whether a screen call is running now. */
    suspend fun callRunning(): Boolean = mutex.withLock { running.values.any { it.isNotEmpty() } }

    /** How many screen calls have started, to tell whether any ran in a stretch of time. */
    val screenCalls: Long
        get() = started.get()

    /** Gives [holder] the screen if no one has it or waits for it. True when it got it. */
    suspend fun claim(holder: Holder): Boolean = mutex.withLock {
        val current = _state.value.holder
        if (current == holder) return@withLock true
        if (current != null || cleaning || queue.isNotEmpty()) return@withLock false
        grant(holder)
        true
    }

    /** Leaves [holder] a note for its next screen call. */
    suspend fun addNote(holder: Holder, message: String) {
        mutex.withLock { note(holder, message) }
    }

    /** Whether [holder] has the screen now. */
    fun holds(holder: Holder): Boolean = _state.value.holder == holder

    /** A viewer's input: takes the screen if no one has it. False means someone else does. */
    suspend fun viewerInput(viewer: Holder.Viewer): Boolean = mutex.withLock {
        val current = _state.value.holder
        when {
            current == viewer -> true
            current == null && !cleaning && queue.isEmpty() -> {
                grant(viewer)
                true
            }
            else -> false
        }
    }

    /** Takes the screen for [viewer] at once, from whoever has it. */
    suspend fun takeOver(viewer: Holder.Viewer) {
        mutex.withLock {
            val current = _state.value
            if (current.holder == viewer) return
            val from =
                when (val holder = current.holder) {
                    is Holder.Session -> holder
                    else -> current.takenFrom
                }
            current.holder?.let { holder ->
                running[holder]?.forEach { job ->
                    taken += job
                    job.cancel(TakenOver())
                }
            }
            _state.value = LeaseState(viewer, now(), from)
        }
    }

    /**
     * Lets the screen go. A viewer that took it over hands it back to that session without cleanup.
     * Otherwise [cleanup] runs, when asked, before the next waiter gets it. A holder that is only
     * waiting leaves the queue.
     */
    suspend fun release(holder: Holder, cleanup: Boolean = true) {
        val clean = mutex.withLock { letGo(holder, cleanup) }
        if (clean) scope.launch { finishCleanup(runCleanup()) }
    }

    /** [release] with [mutex] held. True when cleanup should run. */
    private fun letGo(holder: Holder, cleanup: Boolean): Boolean {
        queue.removeAll { it.holder == holder }
        val current = _state.value
        if (current.takenFrom == holder) _state.value = current.copy(takenFrom = null)
        if (current.holder != holder) return false
        if (holder is Holder.Viewer && current.takenFrom != null) {
            handBack(current, current.takenFrom)
            return false
        }
        _state.value = LeaseState()
        if (!cleanup) grantNext()
        cleaning = cleanup
        return cleanup
    }

    /** While someone waits, gives the screen up for an idle session holder after [graceMs]. */
    private fun watchIdle() {
        if (idleWatch?.isActive == true) return
        idleWatch = scope.launch {
            while (true) {
                val left = mutex.withLock { idleLeft() } ?: return@launch
                if (left > 0) {
                    delay(left)
                    continue
                }
                val clean = mutex.withLock { if (idleLeft() == 0L) yieldIdle() else false }
                if (clean) finishCleanup(runCleanup())
            }
        }
    }

    /**
     * With [mutex] held: how long until the session holder has been idle for [graceMs] while
     * someone waits, 0 once it has, or null when no one waits or the holder is not a session.
     */
    private fun idleLeft(): Long? {
        val holder = _state.value.holder as? Holder.Session ?: return null
        if (queue.isEmpty()) return null
        if (running[holder].orEmpty().isNotEmpty()) return graceMs
        return (lastCallMs + graceMs - now()).coerceAtLeast(0)
    }

    private fun yieldIdle(): Boolean {
        val holder = _state.value.holder ?: return false
        note(
            holder,
            "The screen went to another client after this session made no screen call for " +
                "${graceMs / MS_PER_S} s while it waited. Release cleanup follows the device policy. " +
                "Your next call that needs the screen waits for it. Check the screen before acting.",
        )
        return letGo(holder, cleanup = true)
    }

    private fun handBack(current: LeaseState, back: Holder.Session) {
        note(
            back,
            "A person used the screen from the viewer for ${seconds(current)} s. " +
                "Check the screen before acting.",
        )
        _state.value = LeaseState(back, now())
        lastCallMs = now()
        if (queue.isNotEmpty()) watchIdle()
    }

    private suspend fun runCleanup(): CleanupResult =
        try {
            cleanup()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") // A failed step is reported, not thrown.
            e: Exception) {
            CleanupResult(policy = null, failures = listOf(e.message ?: "cleanup failed"))
        }

    private suspend fun finishCleanup(result: CleanupResult) {
        mutex.withLock {
            cleaning = false
            pendingCleanup = result.note()
            grantNext()
        }
    }

    /** Forgets [holder], as when its session ends or its socket closes. */
    suspend fun forget(holder: Holder) {
        release(holder)
        mutex.withLock { notes.remove(holder) }
    }

    private suspend fun acquire(holder: Holder) {
        val waiter = mutex.withLock {
            val current = _state.value
            if (current.takenFrom == holder) throw taken(current)
            if (current.holder == holder) return
            if (current.holder == null && !cleaning && queue.isEmpty()) {
                grant(holder)
                return
            }
            Waiter(holder, CompletableDeferred()).also {
                queue.addLast(it)
                watchIdle()
            }
        }
        val caller = currentCoroutineContext().job
        val granted =
            try {
                withTimeoutOrNull(waitMs) { waiter.granted.await() } != null
            } finally {
                // A caller canceled while waiting leaves, and gives back a screen granted
                // meanwhile.
                if (!caller.isActive) {
                    withContext(NonCancellable) {
                        if (leaveQueue(waiter)) release(holder, cleanup = false)
                    }
                }
            }
        // The grant can land just as the wait runs out. Then the screen is this holder's.
        if (!granted && !withContext(NonCancellable) { leaveQueue(waiter) }) throw busy()
    }

    /** Takes [waiter] out of the queue. True when it had already been granted the screen. */
    private suspend fun leaveQueue(waiter: Waiter): Boolean = mutex.withLock {
        if (waiter.granted.isCompleted) return@withLock true
        queue.remove(waiter)
        false
    }

    /** Runs [block] as a child that [takeOver] can cancel, turning that into `screen_taken`. */
    private suspend fun <T> runTracked(holder: Holder, block: suspend () -> T): T = coroutineScope {
        val work = async(start = CoroutineStart.LAZY) { block() }
        // The screen can go to a waiter between acquire and here; then wait for it again.
        while (!register(holder, work)) acquire(holder)
        started.incrementAndGet()
        work.start()
        try {
            // join throws only when this caller is canceled, not when take over cancels the child.
            work.join()
            if (work.isCancelled && mutex.withLock { taken.remove(work) }) throw taken(_state.value)
            work.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    running[holder]?.remove(work)
                    if (_state.value.holder == holder) lastCallMs = now()
                }
            }
        }
    }

    /**
     * Records [work] as [holder]'s screen call, if [holder] still has the screen. Under the gate,
     * so betweenCalls cannot miss a call that is about to start.
     */
    private suspend fun register(holder: Holder, work: Job): Boolean = gate.withLock {
        mutex.withLock {
            if (_state.value.holder != holder) {
                false
            } else {
                running.getOrPut(holder) { HashSet() } += work
                lastCallMs = now()
                true
            }
        }
    }

    private fun grant(holder: Holder) {
        _state.value = LeaseState(holder, now())
        lastCallMs = now()
        if (queue.isNotEmpty()) watchIdle()
        pendingCleanup?.let {
            note(holder, it)
            pendingCleanup = null
        }
    }

    private fun grantNext(): Holder? {
        val next = queue.removeFirstOrNull() ?: return null
        grant(next.holder)
        next.granted.complete(Unit)
        return next.holder
    }

    private fun note(holder: Holder, message: String) {
        notes.getOrPut(holder) { mutableListOf() } += message
    }

    /** Takes the notes waiting for [holder], so its next result shows them once. */
    suspend fun takeNotes(holder: Holder): List<String> = mutex.withLock {
        notes.remove(holder).orEmpty()
    }

    private fun seconds(state: LeaseState): Long = (now() - state.sinceMs) / MS_PER_S

    private fun busy(): ApiException {
        val state = _state.value
        val who = if (state.holder is Holder.Viewer) " by a person in the viewer" else ""
        val held = if (state.holder == null) 0 else seconds(state)
        return ApiException(
            409,
            "screen_busy",
            "The screen has been in use$who for $held s. Try again later. Background tabs " +
                "you own still work with input cdp or cdp-mouse.",
        )
    }

    private fun taken(state: LeaseState): ApiException =
        ApiException(
            409,
            "screen_taken",
            "A person in the viewer took the screen ${seconds(state)} s ago. Stop and tell " +
                "the user; the screen comes back when they let go.",
        )

    private class TakenOver : CancellationException("A person in the viewer took the screen")

    companion object {
        const val WAIT_MS = 60_000L
        const val GRACE_MS = 30_000L
        /** A screen call that waited at least this long says so in a note. */
        private const val WAITED_NOTE_MS = 2_000L
        private const val BETWEEN_POLL_MS = 50L
        private const val MS_PER_S = 1_000
    }
}
