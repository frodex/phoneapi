package net.die.phoneapi.core

import java.nio.file.Files
import net.die.phoneapi.model.ScreenCleanupOnRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class SettingsTest {
    @Test
    fun `old settings keep fields`() {
        val settings = ApiJson.decodeFromString<Settings>("""{"port":23456,"keepAwakeMs":3210}""")
        assertEquals(Settings(23_456, 3210), settings)
    }

    @Test
    fun `invalid policy keeps settings`() {
        for (value in listOf("\"unknown\"", "null", "42", "{}")) {
            val settings =
                ApiJson.decodeFromString<Settings>(
                    """{"port":23456,"keepAwakeMs":3210,"screenCleanupOnRelease":$value}"""
                )
            assertEquals(Settings(23_456, 3210), settings)
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `policy persists`(mode: ScreenCleanupOnRelease) {
        val dir = Files.createTempDirectory("phoneapi-cleanup-settings-").toFile()
        val file = dir.resolve("settings.json")
        try {
            file.writeText(
                """{"port":23456,"keepAwakeMs":3210,"screenCleanupOnRelease":"unknown"}"""
            )
            val store = SettingsStore(dir)
            assertEquals(Settings(23_456, 3210), store.current)
            store.update { it.copy(screenCleanupOnRelease = mode) }
            assertEquals(mode, store.settings.value.screenCleanupOnRelease)
            assertEquals(Settings(23_456, 3210, mode), SettingsStore(dir).current)
            assertEquals(
                store.current,
                ApiJson.decodeFromString<Settings>(ApiJson.encodeToString(store.current)),
            )
        } finally {
            file.delete()
            dir.delete()
        }
    }
}
