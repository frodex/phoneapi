package net.die.phoneapi.lease

import kotlin.coroutines.cancellation.CancellationException
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.ScreenCleanupOnRelease

/**
 * Applies the device owner's release policy, sampled once before any action. The default hides the
 * keyboard, shows Chrome's blank placeholder, goes home and locks. Failures travel with the policy
 * snapshot so the next holder receives an accurate account of the cleanup.
 */
class ScreenCleanup(
    private val input: InputService,
    private val power: PowerService,
    private val showPlaceholder: suspend () -> Unit = {},
    private val policy: () -> ScreenCleanupOnRelease = { ScreenCleanupOnRelease.HOME_LOCK },
) {
    suspend fun run(): CleanupResult {
        val selected = policy()
        val failures = buildList {
            if (selected != ScreenCleanupOnRelease.NONE) {
                step("hide the keyboard") { input.hideIme() }
                step("show the blank tab") {
                    showPlaceholder()
                    ActionResult(ok = true)
                }
                step("go home") { input.key(KeyRequest(key = "HOME")) }
                if (selected == ScreenCleanupOnRelease.HOME_LOCK) {
                    step("lock") { power.lock() }
                }
            }
        }
        return CleanupResult(selected, failures)
    }

    private suspend fun MutableList<String>.step(what: String, block: suspend () -> ActionResult) {
        try {
            val result = block()
            if (!result.ok) add("$what: ${result.message ?: "it did not work"}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            add("$what: ${e.message ?: e.error}")
        }
    }
}
