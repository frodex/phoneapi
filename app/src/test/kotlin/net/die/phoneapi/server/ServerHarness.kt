package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.DeviceStateSummary
import net.die.phoneapi.model.DisplayInfo
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.ImeState
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.KeyguardState
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

internal class MemoryTokens : TokenGateway {
    private val bySecret = LinkedHashMap<String, TokenInfo>()

    fun issue(secret: String, scopes: Set<Scope>): String {
        bySecret[secret] = TokenInfo(id = secret, name = secret, scopes = scopes, createdAtMs = 0)
        return secret
    }

    override fun authenticate(secret: String): TokenInfo? = bySecret[secret]

    override fun list(): List<TokenInfo> = bySecret.values.toList()

    override fun create(name: String, scopes: Set<Scope>): CreatedToken {
        val secret = "created-$name"
        val info = TokenInfo(id = name, name = name, scopes = scopes, createdAtMs = 0)
        bySecret[secret] = info
        return CreatedToken(info, secret)
    }

    override fun revoke(id: String): Boolean {
        val secret = bySecret.entries.firstOrNull { it.value.id == id }?.key ?: return false
        bySecret.remove(secret)
        return true
    }
}

internal class RecordingWaits : WaitService {
    var calls = 0
    var request: WaitRequest? = null
    var scopes: Set<Scope>? = null

    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        calls++
        this.request = request
        this.scopes = scopes
        return WaitResult(matched = true, timedOut = false, elapsedMs = 1)
    }
}

/** Fakes of the service interfaces, so [phoneApiModule] can run under `testApplication`. */
@Suppress("InjectDispatcher") // The test server has no injected graph dispatcher.
internal class FakeApi {
    val tokens = MemoryTokens()
    val waits = RecordingWaits()
    var capabilities = allCapabilities()
    var inputTaps = 0
    var imeShow: ImeShowRequest? = null
    var streamBusy = false
    var streamAcquired = false

    val services: ServerServices =
        ServerServices(
            ui = FakeUi,
            input =
                object : InputService {
                    override suspend fun tap(request: TapRequest): ActionResult {
                        inputTaps++
                        return acted()
                    }

                    override suspend fun swipe(request: SwipeRequest) = acted()

                    override suspend fun gesture(request: GestureRequest) = acted()

                    override suspend fun key(request: KeyRequest) = acted()

                    override suspend fun text(request: TextRequest) = acted()

                    override suspend fun hideIme() = acted()

                    override suspend fun showIme(request: ImeShowRequest): ActionResult {
                        imeShow = request
                        return acted()
                    }
                },
            apps = FakeApps,
            waits = waits,
            browser = FakeBrowser,
            power = FakePower,
            tokens = tokens,
            ioDispatcher = Dispatchers.Unconfined,
            device =
                object : DeviceFacts {
                    override fun info(): DeviceInfo = deviceInfo(capabilities)

                    override fun capabilities(): Capabilities = capabilities

                    override fun versionName(): String = "test"
                },
            screenshots = { _ -> byteArrayOf(1, 2, 3) },
            writePin = { _ -> },
            events = emptyFlow(),
            video = VideoFeed { _, _ -> },
            audio = AudioFeed { _ -> },
            inputStreams =
                object : net.die.phoneapi.input.InputStreams {
                    override fun isBusy(): Boolean = streamBusy

                    override fun tryAcquire(): Boolean =
                        if (streamAcquired) {
                            false
                        } else {
                            streamAcquired = true
                            true
                        }

                    override fun release() {
                        streamAcquired = false
                    }

                    override suspend fun serve(session: DefaultWebSocketServerSession) {
                        for (frame in session.incoming) {
                            if (frame is Frame.Close) break
                        }
                    }
                },
            viewerHtml = { VIEWER_HTML },
            shell = { _ -> ShellResult(exit = 0, stdout = "log") },
            cdp = CdpPipes { _ -> IdlePipe },
            viewerText = { "http://127.0.0.1:1/viewer?access_token=TOKEN" },
            logcat = LogcatFeed { _ -> emptyFlow() },
        )

    private object IdlePipe : CdpPipe {
        override fun handshake() = Unit

        override fun connect() = Unit

        override suspend fun relayText(send: suspend (String) -> Unit) = Unit

        override suspend fun sendText(text: String) = Unit

        override fun close() = Unit
    }
}

internal fun ApplicationTestBuilder.apiClient(): HttpClient = createClient {
    expectSuccess = false
    install(WebSockets)
}

internal fun HttpRequestBuilder.bearer(secret: String) {
    header(HttpHeaders.Authorization, "Bearer $secret")
}

internal fun allCapabilities() =
    Capabilities(
        uiSnapshot = true,
        inputInject = true,
        textKeyevent = true,
        screenshotHelper = true,
        uiStableIds = true,
        appsManage = true,
        logcatAll = true,
        browserCdp = true,
        streamVideoMirror = true,
        streamAudioSubmix = true,
        encoderLowLatency = true,
        adbWireless = true,
        settingsSecure = true,
    )

private fun deviceInfo(capabilities: Capabilities) =
    DeviceInfo(
        manufacturer = "test",
        model = "phoneapi-fake",
        sdkInt = 36,
        release = "16",
        appVersion = "test",
        display = DisplayInfo(1080, 2400, 420, 0, 60f),
        state =
            DeviceStateSummary(
                screen = ScreenState.ON,
                keyguard = KeyguardState(locked = false, secure = false),
                ime = ImeState(visible = false),
            ),
        helper = HelperStatus.RUNNING,
        uiAutomationConnected = true,
        capabilities = capabilities,
    )

private fun acted() = ActionResult(ok = true, backend = "fake")

private val snapshot =
    UiSnapshot(
        seq = 1,
        timestampMs = 0,
        state =
            DeviceStateSummary(
                screen = ScreenState.ON,
                keyguard = KeyguardState(locked = false, secure = false),
                ime = ImeState(visible = false),
            ),
        windows = emptyList(),
        compact = "compact",
    )

private object FakeUi : UiService {
    override suspend fun snapshot(options: SnapshotOptions) = snapshot

    override suspend fun find(request: FindRequest) = FindResult(seq = 1, matches = emptyList())

    override suspend fun act(ref: String, request: NodeActionRequest) = acted()
}

private object FakeApps : AppsService {
    override suspend fun list(launchableOnly: Boolean): List<AppInfo> = emptyList()

    override suspend fun launch(packageName: String, request: LaunchRequest) = acted()

    override suspend fun stop(packageName: String) = acted()

    override suspend fun clear(packageName: String) = acted()

    override suspend fun intent(request: IntentRequest) = acted()
}

private object FakeBrowser : BrowserService {
    override suspend fun targets(): List<BrowserTarget> = emptyList()

    override suspend fun openTab(url: String) = BrowserTarget(id = "t", type = "page", socket = "s")

    override suspend fun navigate(id: String, url: String) =
        BrowserTarget(id = id, type = "page", url = url, socket = "s")

    override suspend fun snapshot(id: String) = BrowserSnapshot(id = id, compact = "page")

    override suspend fun tap(id: String, request: BrowserTapRequest) = acted()

    override suspend fun evaluate(id: String, request: EvalRequest) = EvalResult()

    override suspend fun console(id: String, request: ConsoleRequest) = ConsoleResult()
}

private object FakePower : PowerService {
    override suspend fun wake() = acted()

    override suspend fun unlock(request: UnlockRequest) = acted()

    override suspend fun lock() = acted()

    override suspend fun prepareForAction(autoWake: Boolean, allowLocked: Boolean) = false
}

private val VIEWER_HTML = "<html>viewer</html>".encodeToByteArray()
