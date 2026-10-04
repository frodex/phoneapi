package net.die.phoneapi.server

import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InputRouteTest {
    @Test
    fun `show targets a node`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            val focused =
                client.post("/v1/ime/show") {
                    bearer(control)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
            assertEquals(HttpStatusCode.OK, focused.status)
            assertEquals(null, api.imeShow?.selector)
            assertEquals(true, api.imeShow?.autoWake)

            val targeted =
                client.post("/v1/ime/show") {
                    bearer(control)
                    contentType(ContentType.Application.Json)
                    setBody("""{"selector":{"ref":"e12"},"autoWake":false}""")
                }
            assertEquals(HttpStatusCode.OK, targeted.status)
            assertEquals("e12", api.imeShow?.selector?.ref)
            assertEquals(false, api.imeShow?.autoWake)
        }
    }

    @Test
    fun `S6 tap swipe and gesture are 409 while a stream stroke is down`() = testApplication {
        val api = FakeApi()
        api.streamBusy = true
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            for (path in listOf("/v1/input/tap", "/v1/input/swipe", "/v1/input/gesture")) {
                val response =
                    client.post(path) {
                        bearer(control)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                assertEquals(HttpStatusCode.Conflict, response.status, path)
                assertTrue(response.bodyAsText().contains("busy"), path)
            }
            assertEquals(0, api.inputTaps)
        }
    }

    @Test
    fun `S5 a second input stream closes with 1013`() = testApplication {
        val api = FakeApi()
        val control = api.tokens.issue("control", setOf(Scope.CONTROL))
        application { phoneApiModule(api.services) }
        apiClient().use { client ->
            withTimeout(5.seconds) {
                coroutineScope {
                    val first =
                        launch {
                            client.webSocket("/v1/input/stream", { bearer(control) }) {
                                incoming.receive()
                            }
                        }
                    while (!api.streamAcquired) yield()
                    client.webSocket("/v1/input/stream", { bearer(control) }) {
                        val reason = closeReason.await()
                        assertEquals(CloseReason.Codes.TRY_AGAIN_LATER.code, reason?.code)
                        assertEquals(1013.toShort(), reason?.code)
                    }
                    first.cancel()
                }
            }
        }
    }
}
