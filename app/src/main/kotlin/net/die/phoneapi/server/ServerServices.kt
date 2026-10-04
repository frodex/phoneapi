package net.die.phoneapi.server

import io.ktor.server.websocket.DefaultWebSocketServerSession
import java.io.Closeable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.LogcatFilter
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.input.InputStreams
import net.die.phoneapi.stream.VideoSpec

/**
 * The REST and MCP handlers' view of the process. [net.die.phoneapi.AppGraph] builds the real one;
 * tests install [phoneApiModule] with fakes, without constructing Android services.
 */
data class ServerServices(
    val ui: UiService,
    val input: InputService,
    val apps: AppsService,
    val waits: WaitService,
    val browser: BrowserService,
    val power: PowerService,
    val tokens: TokenGateway,
    val ioDispatcher: CoroutineDispatcher,
    val device: DeviceFacts,
    val screenshots: suspend (Float) -> ByteArray,
    val writePin: (String?) -> Unit,
    val events: Flow<Event>,
    val video: VideoFeed,
    val audio: AudioFeed,
    val inputStreams: InputStreams,
    val viewerHtml: suspend () -> ByteArray,
    val shell: suspend (List<String>) -> ShellResult,
    val cdp: CdpPipes,
    val viewerText: () -> String,
    val logcat: LogcatFeed,
)

/** Per-connection logcat. Not part of [ServerServices.events]; only clients that ask receive it. */
fun interface LogcatFeed {
    fun lines(filter: LogcatFilter): Flow<Event>
}

/** Bearer tokens. Implementations store only hashes. */
interface TokenGateway {
    fun authenticate(secret: String): TokenInfo?

    fun list(): List<TokenInfo>

    fun create(name: String, scopes: Set<Scope>): CreatedToken

    fun revoke(id: String): Boolean
}

/** Device identity and the capability map, without the Android types that produce them. */
interface DeviceFacts {
    fun info(): DeviceInfo

    fun capabilities(): Capabilities

    fun versionName(): String
}

fun interface VideoFeed {
    suspend fun serve(session: DefaultWebSocketServerSession, spec: VideoSpec)
}

fun interface AudioFeed {
    suspend fun serve(session: DefaultWebSocketServerSession)
}

/** One raw DevTools socket, already aimed at a browser target. */
interface CdpPipe : Closeable {
    fun handshake()

    fun connect()

    suspend fun relayText(send: suspend (String) -> Unit)

    suspend fun sendText(text: String)
}

fun interface CdpPipes {
    suspend fun open(targetId: String): CdpPipe
}
