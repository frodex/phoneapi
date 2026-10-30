package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.die.phoneapi.model.schema.LenientEnumSerializer

/** The device owner's policy for cleanup when a screen holder lets go. */
@Serializable(with = ScreenCleanupOnReleaseSerializer::class)
public enum class ScreenCleanupOnRelease {
    @SerialName("home-lock") HOME_LOCK,
    @SerialName("home") HOME,
    @SerialName("none") NONE,
}

public object ScreenCleanupOnReleaseSerializer :
    LenientEnumSerializer<ScreenCleanupOnRelease>(
        serialName = "ScreenCleanupOnRelease",
        wires = listOf("home-lock", "home", "none"),
        values = { enumValues<ScreenCleanupOnRelease>() },
    )

public val ScreenCleanupOnRelease.wire: String
    get() = ScreenCleanupOnReleaseSerializer.wire(this)
