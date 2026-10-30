package net.die.phoneapi.lease

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import net.die.phoneapi.core.InputService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.ScreenCleanupOnRelease
import net.die.phoneapi.server.FakeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ScreenCleanupTest {
    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `policy action order`(mode: ScreenCleanupOnRelease) {
        runTest {
            val api = FakeApi()
            val result =
                ScreenCleanup(
                        api.services.input,
                        api.services.power,
                        showPlaceholder = { api.cleanupActions += "placeholder" },
                        policy = { mode },
                    )
                    .run()
            assertEquals(expectedActions(mode), api.cleanupActions)
            assertEquals(CleanupResult(mode), result)
            assertEquals(expectedCleanupNote(mode), result.note())
        }
    }

    @Test
    fun `default cleanup actions`() = runTest {
        val api = FakeApi()
        val result =
            ScreenCleanup(
                    api.services.input,
                    api.services.power,
                    showPlaceholder = { api.cleanupActions += "placeholder" },
                )
                .run()
        assertEquals(expectedActions(ScreenCleanupOnRelease.HOME_LOCK), api.cleanupActions)
        assertEquals(ScreenCleanupOnRelease.HOME_LOCK, result.policy)
    }

    @Test
    fun `policy snapshot per release`() = runTest {
        val api = FakeApi()
        var mode = ScreenCleanupOnRelease.HOME_LOCK
        var reads = 0
        val cleanup =
            ScreenCleanup(
                api.services.input,
                api.services.power,
                showPlaceholder = {
                    api.cleanupActions += "placeholder"
                    mode = ScreenCleanupOnRelease.NONE
                },
                policy = {
                    reads++
                    mode
                },
            )
        val first = cleanup.run()
        assertEquals(expectedActions(ScreenCleanupOnRelease.HOME_LOCK), api.cleanupActions)
        assertEquals(ScreenCleanupOnRelease.HOME_LOCK, first.policy)
        assertEquals(expectedCleanupNote(ScreenCleanupOnRelease.HOME_LOCK), first.note())
        api.cleanupActions.clear()
        val second = cleanup.run()
        assertEquals(ScreenCleanupOnRelease.NONE, second.policy)
        assertEquals(expectedCleanupNote(ScreenCleanupOnRelease.NONE), second.note())
        assertEquals(emptyList<String>(), api.cleanupActions)
        assertEquals(2, reads)
    }

    @Test
    fun `cleanup failures continue`() = runTest {
        val api = FakeApi()
        val input =
            object : InputService by api.services.input {
                override suspend fun hideIme() = ActionResult(ok = false, message = "not hidden")
            }
        val result =
            ScreenCleanup(
                    input,
                    api.services.power,
                    showPlaceholder = { throw ApiException.badRequest("no blank tab") },
                )
                .run()
        assertEquals(
            listOf("hide the keyboard: not hidden", "show the blank tab: no blank tab"),
            result.failures,
        )
        assertEquals(listOf("HOME", "lock"), api.cleanupActions)
        assertEquals(
            "Cleanup after the last holder did not finish: hide the keyboard: not hidden; " +
                "show the blank tab: no blank tab. Check the screen before acting.",
            result.note(),
        )
    }

    @Test
    fun `cancellation stops cleanup`() {
        val api = FakeApi()
        assertThrows(CancellationException::class.java) {
            runTest {
                ScreenCleanup(
                        api.services.input,
                        api.services.power,
                        showPlaceholder = { throw CancellationException("cancelled") },
                    )
                    .run()
            }
        }
        assertEquals(listOf("hide"), api.cleanupActions)
    }
}

/** Actions observed for each public cleanup policy. */
internal fun expectedActions(mode: ScreenCleanupOnRelease): List<String> =
    when (mode) {
        ScreenCleanupOnRelease.HOME_LOCK -> listOf("hide", "placeholder", "HOME", "lock")
        ScreenCleanupOnRelease.HOME -> listOf("hide", "placeholder", "HOME")
        ScreenCleanupOnRelease.NONE -> emptyList()
    }

/** Literal message contract, independent of the production formatter. */
internal fun expectedCleanupNote(mode: ScreenCleanupOnRelease): String =
    when (mode) {
        ScreenCleanupOnRelease.HOME_LOCK ->
            "The screen was cleaned up after the last holder: keyboard hidden, home " +
                "screen, locked. Unlock it before acting."
        ScreenCleanupOnRelease.HOME ->
            "The screen was cleaned up after the last holder: keyboard hidden, blank " +
                "tab, home screen. Release cleanup did not lock it. Check the screen before acting."
        ScreenCleanupOnRelease.NONE ->
            "Release cleanup was disabled by the device owner. Check the screen before acting."
    }
