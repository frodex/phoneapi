package net.die.phoneapi.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonPrimitive
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.ClipImage
import net.die.phoneapi.core.ClipboardService
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.ShellResult
import net.die.phoneapi.lease.CleanupResult
import net.die.phoneapi.lease.ForegroundWatch
import net.die.phoneapi.lease.ScreenCleanup
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserCaptureResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.ClipboardContent
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
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
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.ScreenCleanupOnRelease
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.SessionStarted
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult
import net.die.phoneapi.tabs.TabStore
import net.die.phoneapi.tabs.Tabs

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
internal class FakeApi(leaseWaitMs: Long = ScreenLease.WAIT_MS) {
    val tokens = MemoryTokens()
    val waits = RecordingWaits()
    private val browser = FakeBrowser()
    var capabilities = allCapabilities()
    var inputTaps = 0
    var imeShow: ImeShowRequest? = null
    var orientation: OrientationRequest? = null
    /** The fake phone's clipboard, and what the last clipboard call asked for. */
    @Volatile var clip: String? = "on the phone"
    @Volatile var clipWrite: ClipboardWriteRequest? = null
    @Volatile var clipRead: ClipboardReadRequest? = null
    @Volatile var clipImage: ClipImage? = null
    @Volatile var clipPasted = false
    val pointerSessions = Channel<List<PointerFrame>>(capacity = Channel.UNLIMITED)
    /** Runs after a pointer stream's channel closes and before the pointer call returns. */
    @Volatile var afterPointer: suspend () -> Unit = {}
    @Volatile var cleanups = 0
    @Volatile var cleanupPolicy = ScreenCleanupOnRelease.HOME_LOCK
    val cleanupActions: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val cleanupInput: InputService =
        object : InputService {
            override suspend fun tap(request: TapRequest): ActionResult {
                inputTaps++
                return acted()
            }

            override suspend fun swipe(request: SwipeRequest) = acted()

            override suspend fun gesture(request: GestureRequest) = acted()

            override suspend fun pointer(frames: ReceiveChannel<PointerFrame>) {
                val seen = mutableListOf<PointerFrame>()
                for (frame in frames) seen.add(frame)
                afterPointer()
                pointerSessions.send(seen)
            }

            override suspend fun key(request: KeyRequest): ActionResult {
                cleanupActions += request.key
                return acted()
            }

            override suspend fun text(request: TextRequest) = acted()

            override suspend fun hideIme(): ActionResult {
                cleanupActions += "hide"
                return acted()
            }

            override suspend fun showIme(request: ImeShowRequest): ActionResult {
                imeShow = request
                return acted()
            }
        }

    private val cleanupPower: PowerService =
        object : PowerService by FakePower {
            override suspend fun lock(): ActionResult {
                cleanupActions += "lock"
                return acted()
            }

            override suspend fun orientation(request: OrientationRequest): ActionResult {
                this@FakeApi.orientation = request
                return acted()
            }
        }

    val tabStore = MemoryTabStore()
    val lease: ScreenLease =
        ScreenLease(
            CoroutineScope(Dispatchers.Default),
            cleanup = { releaseCleanup() },
            waitMs = leaseWaitMs,
        )

    /** The package the fake phone has in front, which a test changes to act out a launch. */
    @Volatile var top: String? = "com.launcher"
    val tookScreen = mutableListOf<String>()
    val foreground =
        ForegroundWatch(
            lease,
            CoroutineScope(Dispatchers.Default),
            top = { top },
            bringBack = { top = it },
            report = { _, pkg -> synchronized(tookScreen) { tookScreen += pkg } },
            lateMs = 200,
        )

    val services: ServerServices =
        ServerServices(
            ui = FakeUi,
            input = cleanupInput,
            apps = FakeApps,
            waits = waits,
            browser = browser,
            power = cleanupPower,
            clipboard =
                object : ClipboardService {
                    override suspend fun read(request: ClipboardReadRequest): ClipboardContent {
                        clipRead = request
                        return ClipboardContent(
                            text = clip,
                            mimeTypes =
                                listOfNotNull(clip?.let { "text/plain" }, clipImage?.mimeType),
                            changed = if (request.copy) false else null,
                        )
                    }

                    override suspend fun write(request: ClipboardWriteRequest): ActionResult {
                        clipWrite = request
                        clip = request.text
                        clipImage = null
                        return acted()
                    }

                    override suspend fun image(): ClipImage? = clipImage

                    override suspend fun writeImage(
                        bytes: ByteArray,
                        paste: Boolean,
                    ): ActionResult {
                        clip = null
                        clipImage = ClipImage(bytes, "image/png")
                        clipPasted = paste
                        return acted()
                    }
                },
            tokens = tokens,
            ioDispatcher = Dispatchers.Unconfined,
            device =
                object : DeviceFacts {
                    override fun info(): DeviceInfo =
                        deviceInfo(capabilities).copy(screenCleanupOnRelease = cleanupPolicy)

                    override fun capabilities(): Capabilities = capabilities

                    override fun versionName(): String = "test"
                },
            screenshots = { _ -> byteArrayOf(1, 2, 3) },
            writePin = { _ -> },
            events = emptyFlow(),
            video = VideoFeed { _, _ -> },
            audio = AudioFeed { _ -> },
            viewerHtml = { VIEWER_HTML },
            shell = { _ -> ShellResult(exit = 0, stdout = "log") },
            cdp = CdpPipes { _ -> IdlePipe },
            viewerText = { "http://127.0.0.1:1/viewer?access_token=TOKEN" },
            logcat = LogcatFeed { _ -> emptyFlow() },
            lease = lease,
            tabs = Tabs(browser, lease, inFront = { false }, store = tabStore),
            foreground = foreground,
        )

    private suspend fun releaseCleanup(): CleanupResult {
        val result =
            ScreenCleanup(
                    cleanupInput,
                    cleanupPower,
                    showPlaceholder = { cleanupActions += "placeholder" },
                    policy = { cleanupPolicy },
                )
                .run()
        cleanups++
        return result
    }

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
    followRedirects = false
    install(WebSockets)
}

internal fun HttpRequestBuilder.bearer(secret: String) {
    header(HttpHeaders.Authorization, "Bearer $secret")
}

/** Starts a REST session for [secret], as a script does before acting on the phone. */
internal suspend fun startSession(http: HttpClient, secret: String): String {
    val body = http.post("/v1/sessions") { bearer(secret) }.bodyAsText()
    return ApiJson.decodeFromString(SessionStarted.serializer(), body).id
}

/** Names the REST session a request is for. */
internal fun HttpRequestBuilder.session(id: String) {
    header(SESSION_HEADER, id)
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
        clipboard = true,
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

/** A browser_read with this selector fails, as when the page goes away during the read. */
internal const val FAILING_SELECTOR = "#fails"

/** A browser with one tab, which [BrowserService.openTab] opens and reads and finds answer. */
internal open class FakeBrowser : BrowserService {
    override suspend fun targets(): List<BrowserTarget> = emptyList()

    override suspend fun openTab(url: String, capture: Boolean) =
        BrowserTarget(
            id = "chrome_devtools_remote~t",
            type = "page",
            socket = "chrome_devtools_remote",
        )

    override suspend fun navigate(id: String, url: String) =
        BrowserTarget(id = id, type = "page", url = url, socket = "s")

    override suspend fun capture(id: String, on: Boolean) =
        BrowserCaptureResult(capturing = on, responses = if (on) 2 else 0)

    override suspend fun closeTab(id: String) = acted()

    override suspend fun activate(id: String) = Unit

    override suspend fun showing(id: String) = false

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions) =
        BrowserSnapshot(id = id, compact = "page")

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult {
        if (request.selector == FAILING_SELECTOR) {
            throw ApiException(502, "cdp_error", "The page went away.")
        }
        return BrowserReadResult(
            extracted = "page",
            version = "0",
            totalChars = 4,
            offset = 0,
            returnedChars = 4,
            truncated = false,
            content = "page",
        )
    }

    override suspend fun find(id: String, request: BrowserFindRequest) =
        BrowserFindResult(extracted = "page", version = "0", totalChars = 4, total = 0)

    override suspend fun tap(id: String, request: BrowserTapRequest) = acted()

    override suspend fun swipe(id: String, request: BrowserSwipeRequest) = acted()

    override suspend fun gesture(id: String, request: BrowserGestureRequest) = acted()

    override suspend fun key(id: String, request: BrowserKeyRequest) = acted()

    override suspend fun text(id: String, request: BrowserTextRequest) = acted()

    // A tab a url read opens has loaded at once.
    override suspend fun evaluate(id: String, request: EvalRequest) =
        if (request.expression == "document.readyState")
            EvalResult(value = JsonPrimitive("complete"))
        else EvalResult()

    override suspend fun console(id: String, request: ConsoleRequest) = ConsoleResult()
}

private object FakePower : PowerService {
    override suspend fun wake() = acted()

    override suspend fun unlock(request: UnlockRequest) = acted()

    override suspend fun lock() = acted()

    override suspend fun orientation(request: OrientationRequest) = acted()

    override suspend fun prepareForAction(autoWake: Boolean) = false
}

private val VIEWER_HTML = "<html>viewer</html>".encodeToByteArray()

internal class MemoryTabStore : TabStore {
    var ids: Set<String> = emptySet()

    override fun load(): Set<String> = ids

    override fun save(ids: Set<String>) {
        this.ids = ids
    }
}
