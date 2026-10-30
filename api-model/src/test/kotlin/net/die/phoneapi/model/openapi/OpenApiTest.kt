package net.die.phoneapi.model.openapi

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenApiTest {
    @Test
    fun `device cleanup schema`() {
        val schemas = phoneApiOpenApi()["components"]!!.jsonObject["schemas"]!!.jsonObject
        val device = schemas["DeviceInfo"]!!.jsonObject
        val policy = device["properties"]!!.jsonObject["screenCleanupOnRelease"]!!.jsonObject
        val values = policy["enum"]!!.jsonArray
        assertEquals(listOf("home-lock", "home", "none"), values.map { it.jsonPrimitive.content })
        assertTrue(
            device["required"]!!.jsonArray.none {
                it.jsonPrimitive.content == "screenCleanupOnRelease"
            }
        )
    }

    @Test
    fun `document covers every endpoint`() {
        val document = phoneApiOpenApi()
        assertEquals("3.1.0", document["openapi"]!!.jsonPrimitive.content)
        assertEquals(
            "bearer",
            document["components"]!!.jsonObject["securitySchemes"]!!.jsonObject.keys.single(),
        )
        val paths = document["paths"]!!.jsonObject
        assertEquals(phoneApiEndpoints.map { it.path }.toSet(), paths.keys)
        val sockets = phoneApiEndpoints.filter { it.method == ApiMethod.WEBSOCKET }.map { it.path }
        sockets.forEach { path ->
            val operation = paths[path]!!.jsonObject["get"]!!.jsonObject
            assertEquals(true, operation["x-websocket"]!!.jsonPrimitive.content.toBoolean())
        }
        val device = paths["/v1/device"]!!.jsonObject["get"]!!.jsonObject
        assertEquals("observe", device["x-scope"]!!.jsonPrimitive.content)
        assertTrue(
            document["components"]!!.jsonObject["schemas"]!!.jsonObject.containsKey("DeviceInfo")
        )
        val security = document["security"]!!.jsonArray.single().jsonObject
        assertTrue(security.containsKey("bearer"))
    }
}
