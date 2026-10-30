package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.expectedActions
import net.die.phoneapi.mcp.McpSessions
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenCleanupOnRelease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ReleasePolicyRoutesTest {
    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `REST cleanup paths`(mode: ScreenCleanupOnRelease) {
        for (path in listOf("release", "current", "admin")) testApplication {
            val api = FakeApi().apply { cleanupPolicy = mode }
            val token = api.tokens.issue("owner", Scope.entries.toSet())
            application { phoneApiModule(api.services) }
            apiClient().use { http ->
                val id = startSession(http, token)
                api.lease.withScreen(Holder.Session(id)) {}
                val result =
                    when (path) {
                        "release" ->
                            http.post("/v1/screen/release") {
                                bearer(token)
                                session(id)
                                contentType(ContentType.Application.Json)
                                setBody("""{"screenCleanupOnRelease":"none"}""")
                            }
                        "current" ->
                            http.delete("/v1/sessions/current") {
                                bearer(token)
                                session(id)
                            }
                        else ->
                            http.delete("/v1/sessions/${McpSessions.hashOf(id)}") { bearer(token) }
                    }
                assertEquals(
                    if (path == "release") HttpStatusCode.OK else HttpStatusCode.NoContent,
                    result.status,
                )
                settled(api)
                assertEquals(mode, api.cleanupPolicy)
                assertEquals(expectedActions(mode), api.cleanupActions)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `MCP cleanup paths`(mode: ScreenCleanupOnRelease) {
        for (path in listOf("release", "delete", "child", "parent")) testApplication {
            val api = FakeApi().apply { cleanupPolicy = mode }
            val token = api.tokens.issue("owner", Scope.entries.toSet())
            application { phoneApiModule(api.services) }
            apiClient().use { http ->
                val init =
                    rpc(
                        http,
                        token,
                        null,
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}}""",
                    )
                val id = checkNotNull(init.headers["Mcp-Session-Id"])
                rpc(http, token, id, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
                var held = id
                if (path == "child" || path == "parent") {
                    val child = tool(http, token, id, "session_start", "{}")
                    val text =
                        Json.parseToJsonElement(child)
                            .jsonObject["result"]!!
                            .jsonObject["content"]!!
                            .jsonArray
                            .first()
                            .jsonObject["text"]!!
                            .jsonPrimitive
                            .content
                    // session_start names the child in its first header line.
                    held = Regex("session=([^\\s]+)").find(text)?.groupValues?.get(1) ?: error(text)
                }
                api.lease.withScreen(Holder.Session(held)) {}
                when (path) {
                    "release" -> tool(http, token, id, "screen_release", "{}")
                    "child" -> tool(http, token, id, "session_end", """{"session":"$held"}""")
                    else ->
                        assertEquals(
                            HttpStatusCode.OK,
                            http
                                .delete("/mcp") {
                                    bearer(token)
                                    header("Mcp-Session-Id", id)
                                    header("MCP-Protocol-Version", "2025-06-18")
                                }
                                .status,
                        )
                }
                settled(api)
                assertEquals(expectedActions(mode), api.cleanupActions)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `expiry and revocation cleanup`(mode: ScreenCleanupOnRelease) {
        for (revoke in listOf(false, true)) testApplication {
            val api = FakeApi().apply { cleanupPolicy = mode }
            val token = api.tokens.issue("owner", Scope.entries.toSet())
            var clock = 0L
            lateinit var sessions: McpSessions
            application {
                sessions =
                    McpSessions(
                        api.tokens,
                        this,
                        now = { clock },
                        onEnd = {
                            api.lease.forget(Holder.Session(it))
                            api.services.tabs.end(Holder.Session(it))
                        },
                    )
                phoneApiModule(api.services, sessions)
            }
            apiClient().use { http ->
                val id = startSession(http, token)
                api.lease.withScreen(Holder.Session(id)) {}
                if (revoke) api.tokens.revoke(token) else clock = McpSessions.IDLE_MS + 1
                sessions.sweep()
                settled(api)
                assertEquals(expectedActions(mode), api.cleanupActions)
                assertEquals(0, sessions.list().size)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ScreenCleanupOnRelease::class)
    fun `device policy without lease`(mode: ScreenCleanupOnRelease) {
        testApplication {
            val api = FakeApi()
            val token = api.tokens.issue("reader", setOf(Scope.OBSERVE))
            application { phoneApiModule(api.services) }
            apiClient().use { http ->
                http.get("/v1/device") { bearer(token) }
                api.cleanupPolicy = mode
                val response = http.get("/v1/device") { bearer(token) }
                assertEquals(HttpStatusCode.OK, response.status)
                val info = Json.decodeFromString<DeviceInfo>(response.bodyAsText())
                assertEquals(mode, info.screenCleanupOnRelease)
                assertEquals(null, api.lease.state.value.holder)
                assertEquals(0, api.cleanups)
                assertFalse(api.cleanupActions.isNotEmpty())
            }
        }
    }

    private suspend fun settled(api: FakeApi) {
        withTimeout(5_000) { while (api.cleanups != 1) delay(10) }
    }

    private suspend fun rpc(http: HttpClient, token: String, id: String?, body: String) =
        http.post("/mcp") {
            bearer(token)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            header("MCP-Protocol-Version", "2025-06-18")
            if (id != null) header("Mcp-Session-Id", id)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun tool(
        http: HttpClient,
        token: String,
        id: String,
        name: String,
        args: String,
    ): String {
        val response =
            rpc(
                http,
                token,
                id,
                """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$name","arguments":$args}}""",
            )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains("\"isError\":true"), body)
        return body
    }
}
