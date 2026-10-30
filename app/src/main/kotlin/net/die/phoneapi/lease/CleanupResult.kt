package net.die.phoneapi.lease

import net.die.phoneapi.model.ScreenCleanupOnRelease

/** The policy used for a release, and the steps that failed. Null means cleanup threw. */
data class CleanupResult(
    val policy: ScreenCleanupOnRelease? = ScreenCleanupOnRelease.HOME_LOCK,
    val failures: List<String> = emptyList(),
)

/** A note about completed cleanup, never about the current, possibly changed, setting. */
fun CleanupResult.note(): String =
    if (failures.isNotEmpty()) {
        "Cleanup after the last holder did not finish: ${failures.joinToString("; ")}. " +
            "Check the screen before acting."
    } else
        when (policy) {
            ScreenCleanupOnRelease.HOME_LOCK ->
                "The screen was cleaned up after the last holder: keyboard hidden, home " +
                    "screen, locked. Unlock it before acting."
            ScreenCleanupOnRelease.HOME ->
                "The screen was cleaned up after the last holder: keyboard hidden, blank " +
                    "tab, home screen. Release cleanup did not lock it. " +
                    "Check the screen before acting."
            ScreenCleanupOnRelease.NONE ->
                "Release cleanup was disabled by the device owner. Check the screen before acting."
            null -> "Check the screen before acting."
        }
