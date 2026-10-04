package net.die.phoneapi

import android.content.Context
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import net.die.phoneapi.apps.AppsServiceImpl
import net.die.phoneapi.browser.BrowserServiceImpl
import net.die.phoneapi.browser.CdpForward
import net.die.phoneapi.browser.DevtoolsPreconditions
import net.die.phoneapi.browser.DevtoolsSocket
import net.die.phoneapi.browser.HelperCdpPipes
import net.die.phoneapi.browser.USB_DEVTOOLS_SOCKET
import net.die.phoneapi.browser.UsbDevtools
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.DeviceInfoProvider
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.InputService
import net.die.phoneapi.core.PowerService
import net.die.phoneapi.core.Screenshotter
import net.die.phoneapi.core.SettingsStore
import net.die.phoneapi.core.UiService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.helperclient.HelperLogcatFeed
import net.die.phoneapi.helperclient.HelperShell
import net.die.phoneapi.helperclient.HelperStatusNotifier
import net.die.phoneapi.helperclient.HelperSupervisor
import net.die.phoneapi.helperclient.KeystorePrivateKeyStore
import net.die.phoneapi.helperclient.WirelessPairing
import net.die.phoneapi.input.Humanizer
import net.die.phoneapi.input.InjectKeyBackend
import net.die.phoneapi.input.InjectTouchBackend
import net.die.phoneapi.input.InputStreamHub
import net.die.phoneapi.input.InputServiceImpl
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.power.PinStore
import net.die.phoneapi.power.PowerServiceImpl
import net.die.phoneapi.server.ApiServer
import net.die.phoneapi.server.AudioFeed
import net.die.phoneapi.server.DeviceFacts
import net.die.phoneapi.server.NetworkWatcher
import net.die.phoneapi.server.ServerController
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.TokenStore
import net.die.phoneapi.server.VideoFeed
import net.die.phoneapi.stream.AudioStream
import net.die.phoneapi.stream.StreamLease
import net.die.phoneapi.stream.VideoStream
import net.die.phoneapi.tree.HelperUiService
import net.die.phoneapi.tree.TreeSession
import net.die.phoneapi.wait.WaitServiceImpl

/** Process-wide object graph, created once by [PhoneApiApp]. */
class AppGraph(
    val context: Context,
    filesDir: File = context.filesDir,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val bus = EventBus()
    val settings = SettingsStore(filesDir)
    val tokens = TokenStore(filesDir)
    val state = DeviceStateTracker(context, bus)
    val helper =
        HelperConnection(
            bus,
            idleStatus =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HelperStatus.NEEDS_PAIRING
                } else {
                    HelperStatus.NEEDS_USB
                },
        )
    val shell = HelperShell(helper, ioDispatcher)
    val network = NetworkWatcher(context)
    private val adbKeys = KeystorePrivateKeyStore(filesDir)
    internal val helperSupervisor =
        HelperSupervisor(
            context = context,
            helper = helper,
            keys = adbKeys,
            network = network,
            scope = scope,
            ioDispatcher = ioDispatcher,
        )
    private val cdpForward = CdpForward(context, adbKeys, ioDispatcher)
    private val usbDevtools =
        UsbDevtools(
            openSocket = { helper.require().openAbstractSocket(USB_DEVTOOLS_SOCKET) },
            io = ioDispatcher,
        )
    internal val wirelessPairing = WirelessPairing(context, helperSupervisor, scope, ioDispatcher)

    /** The lock-screen PIN, provisioned over ADB and never returned by the API. */
    val pins = PinStore(filesDir)

    @Volatile private var uiAutomationConnected = false

    val deviceInfo =
        DeviceInfoProvider(
            context = context,
            state = state,
            isHelperRunning = { helper.isRunning },
            isUiAutomationConnected = { uiAutomationConnected },
            helperStatus = { helper.status.value },
            helperRecoveredAtMs = { helper.recoveredAtMs },
        )

    val tree =
        TreeSession(
            helper = helper,
            bus = bus,
            state = state,
            display = { deviceInfo.display() },
            onSession = { up -> uiAutomationConnected = up },
        )

    val keys = InjectKeyBackend(helper, ioDispatcher)
    val touchBackend = InjectTouchBackend(helper, ioDispatcher)
    val touch = TouchInput(touchBackend, Humanizer()) { deviceInfo.display() }
    val inputStreams =
        InputStreamHub(
            now = { android.os.SystemClock.uptimeMillis() },
            bounds = { deviceInfo.logicalSize() },
            inject = { event -> touchBackend.injectOne(event) },
        )
    val screenshots = Screenshotter(ioDispatcher, ::helperScreenshot)
    val power: PowerService =
        PowerServiceImpl(
            context = context,
            settings = settings,
            seq = tree.seq,
            tree = tree,
            state = state,
            bus = bus,
            shell = shell,
            pins = pins,
            touch = touch,
            display = { deviceInfo.display() },
            io = ioDispatcher,
        )
    val ui: UiService =
        HelperUiService(
            prepare = power::prepareForAction,
            tree = tree,
            touch = touch,
            seq = tree.seq,
        )
    val input: InputService =
        InputServiceImpl(
            prepare = power::prepareForAction,
            io = ioDispatcher,
            tree = tree,
            seq = tree.seq,
            touch = touch,
            keys = keys,
            state = state,
            display = { deviceInfo.display() },
        )
    val apps: AppsService =
        AppsServiceImpl(
            context = context,
            io = ioDispatcher,
            prepare = power::prepareForAction,
            invalidateSnapshots = tree::invalidate,
            seq = tree.seq,
            shell = shell,
            state = state,
            bus = bus,
        )
    val browser: BrowserService = browserService()
    val waits: WaitService =
        WaitServiceImpl(
            prepare = power::prepareForAction,
            tree = tree,
            state = state,
            bus = bus,
            io = ioDispatcher,
            browser = browser,
            openDevtools = ::openDevtools,
        )
    internal val video =
        VideoStream(
            StreamLease(context, settings),
            helper,
            { deviceInfo.display() },
            context,
            ioDispatcher,
        )
    internal val audio = AudioStream(StreamLease(context, settings), helper, ioDispatcher)

    val services =
        ServerServices(
            ui = ui,
            input = input,
            apps = apps,
            waits = waits,
            browser = browser,
            power = power,
            tokens = tokens,
            ioDispatcher = ioDispatcher,
            device = GraphDevice(deviceInfo),
            screenshots = { scale -> this.screenshots.png(scale) },
            writePin = { pin -> pins.write(pin) },
            events = bus.events,
            video = VideoFeed { session, spec -> this.video.serve(session, spec) },
            audio = AudioFeed { session -> this.audio.serve(session) },
            inputStreams = this.inputStreams,
            viewerHtml = { asset("viewer.html") },
            shell = { argv -> this.shell.exec(argv) },
            cdp = HelperCdpPipes(::openDevtools, ioDispatcher),
            viewerText = { viewerLink() },
            logcat = HelperLogcatFeed(helper, bus, ioDispatcher),
        )

    val server = ApiServer(services, socketName = context.packageName)
    val serverController = ServerController(scope, server)

    private suspend fun asset(name: String): ByteArray =
        withContext(ioDispatcher) { context.assets.open(name).use { it.readBytes() } }

    @Suppress("MissingUseCall") // The caller closes the socket.
    private suspend fun openDevtools(name: String, readTimeoutMs: Long): DevtoolsSocket =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            cdpForward.open(name, readTimeoutMs)
        } else {
            usbDevtools.open(name, readTimeoutMs)
        }

    @Suppress("MissingUseCall")
    private fun browserService(): BrowserService =
        BrowserServiceImpl(
            io = ioDispatcher,
            listSockets = { helper.require().listDevtoolsSockets() },
            open = { name -> openDevtools(name, DEVTOOLS_READ_MS) },
            contentBounds = { pkg -> tree.contentBounds(pkg.orEmpty()) },
            touchAt = { rect, humanize ->
                val outcome = touch.tap(rect, humanize = humanize)
                ActionResult(
                    ok = outcome.ok,
                    backend = outcome.backend,
                    points = outcome.points,
                    message = if (outcome.ok) null else "The system cancelled the gesture",
                )
            },
            prepare = { autoWake -> power.prepareForAction(autoWake) },
            sequence = { tree.seq.value },
            invalidateSnapshots = { tree.invalidate() },
            showPackage = { pkg ->
                shell.exec(
                    listOf(
                        "am",
                        "start",
                        "-a",
                        "android.intent.action.MAIN",
                        "-c",
                        "android.intent.category.LAUNCHER",
                        "-p",
                        pkg,
                    )
                )
            },
            devtoolsGap = DevtoolsPreconditions(context)::explain,
        )

    fun start() {
        state.start()
        network.start()
        tree.start(scope)
        helper.onReady = { proxy -> tree.attach(proxy) }
        HelperStatusNotifier(context, helper).start(scope)
        helperSupervisor.start()
    }

    private suspend fun helperScreenshot(): ByteArray? {
        val proxy = helper.require()
        val pipe = withContext(ioDispatcher) { proxy.screencap() } ?: return null
        return withContext(ioDispatcher) {
            try {
                FileInputStream(pipe.fileDescriptor).use { it.readBytes() }
            } finally {
                pipe.close()
            }
        }
    }

    fun screenRect(): Rect = deviceInfo.display().let { Rect(0, 0, it.widthPx, it.heightPx) }

    private fun viewerLink(): String {
        val port = settings.current.port
        return "http://127.0.0.1:$port/viewer?access_token=TOKEN\n" +
            "Replace TOKEN with this device's bearer token. " +
            "The query parameter is accepted only by GET /viewer and by WebSocket upgrades. " +
            "Expose the server on the computer first with `phoneapi server` " +
            "(or `phoneapi pair` to mint a token). " +
            "Expected local port $port for package ${context.packageName}."
    }

    private companion object {
        const val DEVTOOLS_READ_MS = 15_000L
    }
}

private class GraphDevice(private val provider: DeviceInfoProvider) : DeviceFacts {
    override fun info() = provider.info()

    override fun capabilities() = provider.capabilities()

    override fun versionName() = provider.versionName()
}
