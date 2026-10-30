package net.die.phoneapi.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import net.die.phoneapi.model.ScreenCleanupOnRelease

/** An unknown stored policy defaults safely without resetting unrelated device settings. */
internal object StoredScreenCleanupSerializer :
    JsonTransformingSerializer<ScreenCleanupOnRelease>(ScreenCleanupOnRelease.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (
            runCatching {
                ApiJson.decodeFromJsonElement(ScreenCleanupOnRelease.serializer(), element)
            }
                .isSuccess
        )
            element
        else JsonPrimitive("home-lock")
}
