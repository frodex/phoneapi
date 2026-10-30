package net.die.phoneapi

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.apps.AppsServiceImpl
import net.die.phoneapi.apps.RESUMED_ACTIVITY_ARGS
import net.die.phoneapi.apps.resumedPackage
import net.die.phoneapi.browser.BrowserServiceImpl
import net.die.phoneapi.browser.CHROME_PACKAGE
import net.die.phoneapi.browser.CdpForward
import net.die.phoneapi.browser.ChromeKeeper
import net.die.phoneapi.browser.DevtoolsPreconditions
import net.die.phoneapi.browser.DevtoolsSocket
import net.die.phoneapi.browser.FREEZER_ARGS
import net.die.phoneapi.browser.HelperCdpPipes
import net.die.phoneapi.browser.TabProbe
import net.die.phoneapi.browser.USB_DEVTOOLS_SOCKET
import net.die.phoneapi.browser.UsbDevtools
import net.die.phoneapi.browser.freezerOn
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.ClipboardService
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
import net.die.phoneapi.input.ClipboardServiceImpl
import net.die.phoneapi.input.Humanizer
import net.die.phoneapi.input.InjectKeyBackend
import net.die.phoneapi.input.InjectTouchBackend
import net.die.phoneapi.input.InputServiceImpl
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.input.TouchOutcome
import net.die.phoneapi.lease.ForegroundWatch
import net.die.phoneapi.lease.ScreenCleanup
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.mcp.McpSessions
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.EventTypes
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.ScreenState
import net.die.phoneapi.model.ScreenTakenData
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
import net.die.phoneapi.tabs.FileTabStore
import net.die.phoneapi.tabs.Tabs
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

    private val chromeKeeper = ChromeKeeper(context)
    @Volatile private var appFreezer: Boolean? = null

    val deviceInfo =
        DeviceInfoProvider(
            context = context,
            state = state,
            isHelperRunning = { helper.isRunning },
            isUiAutomationConnected = { uiAutomationConnected },
            helperStatus = { helper.status.value },
            helperRecoveredAtMs = { helper.recoveredAtMs },
            appFreezer = { appFreezer },
            chromeKept = { chromeKeeper.held },
            screenCleanupOnRelease = { settings.current.screenCleanupOnRelease },
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
    private val touchBackend = InjectTouchBackend(helper, ioDispatcher)
    val touch = TouchInput(touchBackend, Humanizer()) { deviceInfo.display() }
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
    val clipboard: ClipboardService = ClipboardServiceImpl(context, helper, input, ioDispatcher)
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
    // Cleanup runs only after a holder lets go, long after tabs exists.
    @Suppress("PropertyUsedBeforeDeclaration")
    private val screenLease: ScreenLease =
        ScreenLease(
            scope,
            cleanup =
                ScreenCleanup(
                    input,
                    power,
                    showPlaceholder = { tabs.showPlaceholder() },
                    policy = { settings.current.screenCleanupOnRelease },
                )::run,
        )
    private val tabs: Tabs =
        Tabs(
            browser,
            screenLease,
            inFront = { pkg -> appInFront(pkg) },
            store = FileTabStore(File(filesDir, "session-tabs")),
            scope = scope,
        )
    val waits: WaitService =
        WaitServiceImpl(
            prepare = power::prepareForAction,
            tree = tree,
            state = state,
            bus = bus,
            io = ioDispatcher,
            browser = browser,
            openDevtools = ::openDevtools,
            dialogFrom = { host -> chromeDialogShown(host) },
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
            clipboard = clipboard,
            tokens = tokens,
            ioDispatcher = ioDispatcher,
            device = GraphDevice(deviceInfo),
            screenshots = { scale -> this.screenshots.png(scale) },
            writePin = { pin -> pins.write(pin) },
            events = bus.events,
            video = VideoFeed { session, spec -> this.video.serve(session, spec) },
            audio = AudioFeed { session -> this.audio.serve(session) },
            viewerHtml = { asset("viewer.html") },
            shell = { argv -> this.shell.exec(argv) },
            cdp = HelperCdpPipes(::openDevtools, ioDispatcher),
            viewerText = { viewerLink() },
            logcat = HelperLogcatFeed(helper, bus, ioDispatcher),
            lease = screenLease,
            tabs = tabs,
            foreground =
                ForegroundWatch(
                    screenLease,
                    scope,
                    top = { topPackage() },
                    bringBack = { pkg -> bringBack(pkg) },
                    report = { holder, pkg -> tookScreen(McpSessions.hashOf(holder.id), pkg) },
                ),
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

    private fun touched(outcome: TouchOutcome) =
        ActionResult(
            ok = outcome.ok,
            backend = outcome.backend,
            points = outcome.points,
            message = if (outcome.ok) null else "The system cancelled the gesture",
        )

    /**
     * Screen on, keyguard down, and [pkg]'s task is on top, even with no web content showing, such
     * as Chrome's start screen. Asks the helper, since foregroundPackage can be stale.
     */
    private suspend fun appInFront(pkg: String): Boolean {
        val now = state.refresh()
        if (now.screen != ScreenState.ON || now.keyguard.locked) return false
        return topPackage() == pkg
    }

    /**
     * Whether Chrome shows a page's dialog, and with [host], one whose title names it, as in
     * "example.com says". Chrome draws an alert, confirm, or prompt in its own views, whose ids may
     * change between releases; that only loses what this tells.
     */
    private suspend fun chromeDialogShown(host: String? = null): Boolean =
        try {
            shown(NodeSelector(id = CHROME_DIALOG_ID, packageName = CHROME_PACKAGE)) &&
                (host == null ||
                    shown(
                        NodeSelector(
                            id = CHROME_DIALOG_TITLE_ID,
                            textContains = host,
                            packageName = CHROME_PACKAGE,
                        )
                    ))
        } catch (_: ApiException) {
            false
        }

    private suspend fun shown(selector: NodeSelector): Boolean =
        ui.find(FindRequest(selector, limit = 1)).matches.isNotEmpty()

    private suspend fun launchPackage(pkg: String) =
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

    private suspend fun readFreezer(): Boolean? =
        try {
            freezerOn(shell.exec(FREEZER_ARGS).stdout)
        } catch (_: ApiException) {
            null
        }

    /**
     * The package of the top task, or null when the helper cannot tell. The helper asks the
     * activity task manager directly, in milliseconds; `dumpsys` is the fallback for a release
     * where that call fails, at about 100 ms on a phone.
     */
    private suspend fun topPackage(): String? {
        val proxy =
            try {
                helper.require()
            } catch (_: ApiException) {
                return null
            }
        val direct = withContext(ioDispatcher) { runCatching { proxy.topPackage() } }
        direct.onSuccess {
            return it
        }
        return try {
            val activities = shell.exec(RESUMED_ACTIVITY_ARGS)
            if (activities.ok) resumedPackage(activities.stdout) else null
        } catch (_: ApiException) {
            null
        }
    }

    /** Puts [pkg] back in front, or goes home when it has no launcher entry, as a launcher. */
    private suspend fun bringBack(pkg: String) {
        try {
            val launched = launchPackage(pkg)
            if (!launched.ok || launched.stdout.contains("Error"))
                input.key(KeyRequest(key = "HOME"))
        } catch (_: ApiException) {
            // The helper is gone; the holder's next result says to check the screen.
        }
    }

    private fun tookScreen(session: String, pkg: String) {
        Log.w(TAG, "MCP session $session brought $pkg forward without holding the screen")
        bus.emit(EventTypes.SCREEN_TAKEN, ScreenTakenData(session, pkg))
    }

    @Suppress("MissingUseCall") // BrowserServiceImpl closes each socket it opens.
    private fun browserService(): BrowserService =
        BrowserServiceImpl(
            io = ioDispatcher,
            listSockets = { helper.require().listDevtoolsSockets() },
            open = { name -> openDevtools(name, DEVTOOLS_READ_MS) },
            openProbe = { name -> openDevtools(name, TabProbe.PROBE_MS) },
            openCapture = { name -> openDevtools(name, 0) },
            contentBounds = { pkg -> tree.contentBounds(pkg.orEmpty()) },
            touchAt = { rect, humanize ->
                val outcome = touch.tap(rect, humanize = humanize)
                touched(outcome)
            },
            swipeAt = { from, to, spec -> touched(touch.swipe(from, to, spec)) },
            gestureAt = { pointers, humanize -> touched(touch.gesture(pointers, humanize)) },
            pressKey = { request -> input.key(request) },
            typeText = { request -> input.text(request) },
            prepare = { autoWake -> power.prepareForAction(autoWake) },
            sequence = { tree.seq.value },
            invalidateSnapshots = { tree.invalidate() },
            showPackage = { pkg -> launchPackage(pkg) },
            inFront = { pkg -> appInFront(pkg) },
            pageDialog = { chromeDialogShown() },
            pageScript = {
                asset("readability/Readability.js").decodeToString() +
                    "\n" +
                    asset("page-pending.js").decodeToString() +
                    "\n" +
                    asset("page-data.js").decodeToString() +
                    "\n" +
                    asset("page-read.js").decodeToString()
            },
            dataScript = { asset("page-data.js").decodeToString() },
            pendingScript = { asset("page-pending.js").decodeToString() },
            devtoolsGap = DevtoolsPreconditions(context)::explain,
            refreshHz = { deviceInfo.display().refreshRate },
            touchscreen = { touchBackend.touchscreen() },
            scope = scope,
        )

    fun start() {
        state.start()
        network.start()
        tree.start(scope)
        helper.onReady = { proxy ->
            tree.attach(proxy)
            scope.launch { tabs.sweep() }
            scope.launch { appFreezer = readFreezer() }
        }
        // Chrome stays unfrozen whenever the helper runs, whether or not anyone is connected.
        scope.launch {
            helper.status.collect { status ->
                if (status == HelperStatus.RUNNING) chromeKeeper.hold() else chromeKeeper.drop()
            }
        }
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
            "That URL sets a cookie and redirects to /viewer without the token. " +
            "The query parameter is also accepted on WebSocket upgrades. " +
            "Expose the server on the computer first with `phoneapi server` " +
            "(or `phoneapi pair` to mint a token). " +
            "Expected local port $port for package ${context.packageName}."
    }

    private companion object {
        const val DEVTOOLS_READ_MS = 15_000L

        /** The view Chrome draws a page's dialog in. */
        const val CHROME_DIALOG_ID = "tab_modal_dialog_container"

        /** The dialog's title, which names the page's host. */
        const val CHROME_DIALOG_TITLE_ID = "title"
        const val TAG = "PhoneApiLease"
    }
}

private class GraphDevice(private val provider: DeviceInfoProvider) : DeviceFacts {
    override fun info() = provider.info()

    override fun capabilities() = provider.capabilities()

    override fun versionName() = provider.versionName()
}
