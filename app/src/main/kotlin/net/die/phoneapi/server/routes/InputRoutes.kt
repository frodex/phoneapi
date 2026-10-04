package net.die.phoneapi.server.routes

import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import net.die.phoneapi.input.SECOND_STREAM_CLOSE
import net.die.phoneapi.input.streamBusyException
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.scoped

/** `POST /v1/input/tap|swipe|gesture|key|text`, `WS /v1/input/stream`, and `POST /v1/ime/hide|show`. */
fun Route.inputRoutes(services: ServerServices) {
    scoped(Scope.CONTROL) {
        post("/v1/input/tap") {
            rejectIfStreaming(services)
            call.respond(services.input.tap(call.receive<TapRequest>()))
        }
        post("/v1/input/swipe") {
            rejectIfStreaming(services)
            call.respond(services.input.swipe(call.receive<SwipeRequest>()))
        }
        post("/v1/input/gesture") {
            rejectIfStreaming(services)
            call.respond(services.input.gesture(call.receive<GestureRequest>()))
        }
        webSocket("/v1/input/stream") {
            if (!services.inputStreams.tryAcquire()) {
                close(SECOND_STREAM_CLOSE)
                return@webSocket
            }
            try {
                services.inputStreams.serve(this)
            } finally {
                services.inputStreams.release()
            }
        }
        post("/v1/input/key") { call.respond(services.input.key(call.receive<KeyRequest>())) }
        post("/v1/input/text") { call.respond(services.input.text(call.receive<TextRequest>())) }
        post("/v1/ime/hide") { call.respond(services.input.hideIme()) }
        post("/v1/ime/show") {
            call.respond(services.input.showIme(call.receive<ImeShowRequest>()))
        }
    }
}

private fun rejectIfStreaming(services: ServerServices) {
    if (services.inputStreams.isBusy()) throw streamBusyException()
}
