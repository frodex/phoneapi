package net.die.phoneapi.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import net.die.phoneapi.mcp.McpSessions
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SessionStarted
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.anyToken
import net.die.phoneapi.server.scoped
import net.die.phoneapi.server.session
import net.die.phoneapi.server.token

/**
 * Sessions on REST: starting one, ending it, and letting its screen go. The admin routes list
 * sessions and end a stuck one by the hash of its id.
 */
fun Route.sessionRoutes(services: ServerServices, sessions: McpSessions) {
    // Any token can start, end, and release its own session, whatever its scopes.
    anyToken {
        post("/v1/sessions") {
            val id = sessions.startRest(call.token.id, call.token.name)
            call.respond(HttpStatusCode.Created, SessionStarted(id, McpSessions.IDLE_MS))
        }
        delete("/v1/sessions/current") {
            sessions.end(call.session().id, "ended by its client")
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/screen/release") {
            val holder = call.session()
            val held = services.lease.holds(holder)
            services.lease.release(holder)
            val message =
                if (held) {
                    "Released the screen. PhoneAPI applies the device owner’s configured cleanup " +
                        "policy before the next client gets it."
                } else {
                    "This session did not hold the screen."
                }
            call.respond(ActionResult(ok = true, message = message))
        }
    }
    scoped(Scope.ADMIN) {
        get("/v1/sessions") { call.respond(sessions.list()) }
        delete("/v1/sessions/{hash}") {
            val hash = call.parameters["hash"] ?: throw ApiException.badRequest("hash required")
            if (!sessions.endByHash(hash)) throw ApiException.notFound("session $hash")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
