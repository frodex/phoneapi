package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ContentBlock
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.ScreenshotScale
import net.die.phoneapi.core.checkLogTag
import net.die.phoneapi.core.windowId
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BROWSER_INPUT_DOC
import net.die.phoneapi.model.BROWSER_WAKE_DOC
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.CAPTURE_OPEN_DOC
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.DEFAULT_TAP_WAIT_MS
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.SHOW_DOC
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SnapshotFormat
import net.die.phoneapi.model.SnapshotOptions
import net.die.phoneapi.model.SwipeDirection
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TAP_WAIT_DOC
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextMode
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TimedPoint
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.tabs.withTarget
import net.die.phoneapi.wait.requireWaitAccess

internal suspend fun deviceInfo(services: ServerServices): CallToolResult = runTool {
    val info = services.device.info()
    val caller = services.holder
    val control = services.sessions
    val state = services.lease.state.value
    jsonText(
        if (caller != null && control != null) control.withScreenHolder(info, state, caller)
        else info
    )
}

internal suspend fun uiSnapshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<SnapshotArgs>()
    val snapshot =
        services.ui.snapshot(
            SnapshotOptions(
                format = SnapshotFormat.COMPACT,
                windowId = args.window?.let(::windowId),
                maxDepth = args.maxDepth,
                maxNodes = args.maxNodes,
                maxChars = args.maxChars,
                textOnly = args.textOnly,
                redact = args.redact,
                includeInvisible = args.includeInvisible,
                allWindows = args.all,
                autoWake = args.autoWake,
            )
        )
    snapshot.compact ?: "(empty snapshot)"
}

internal suspend fun uiFind(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<UiFindArgs>()
        jsonText(
            services.ui.find(
                FindRequest(args.selector, args.limit, args.includeInvisible, args.redact)
            )
        )
    }

internal suspend fun uiAct(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<NodeActArgs>()
        jsonText(
            services.ui.act(
                args.ref,
                NodeActionRequest(
                    action = args.action,
                    mode = args.mode,
                    text = args.text,
                    force = args.force,
                    autoWake = args.autoWake,
                ),
            )
        )
    }

internal suspend fun tap(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.tap(request.args<TapRequest>()))
    }

internal suspend fun swipe(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.swipe(request.args<SwipeRequest>()))
    }

internal suspend fun typeText(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.text(request.args<TextRequest>()))
    }

internal suspend fun pressKey(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.input.key(request.args<KeyRequest>()))
    }

internal suspend fun clipboardRead(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runContent {
    val clip = services.clipboard.read(request.args<ClipboardReadRequest>())
    val image =
        if (clip.text == null && clip.mimeTypes.any { it.startsWith("image/") }) {
            services.clipboard.image()
        } else {
            null
        }
    when {
        image == null -> listOf(TextContent(text = jsonText(clip)))
        image.bytes.size > MAX_IMAGE_BYTES ->
            listOf(
                TextContent(text = jsonText(clip)),
                TextContent(
                    text =
                        "The image is ${image.bytes.size} bytes, too large to return here. " +
                            "GET /v1/clipboard/image returns it."
                ),
            )
        else ->
            listOf(
                TextContent(text = jsonText(clip)),
                ImageContent(
                    data = Base64.getEncoder().encodeToString(image.bytes),
                    mimeType = image.mimeType,
                ),
            )
    }
}

internal suspend fun clipboardWrite(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.clipboard.write(request.args<ClipboardWriteRequest>()))
}

internal suspend fun waitFor(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runContent {
    val args = request.args<WaitArgs>()
    val wait =
        WaitRequest(
            all = args.all.map { withTarget(it, args.target) },
            any = args.any.map { withTarget(it, args.target) },
            timeoutMs = args.timeoutMs,
            settleMs = args.settleMs,
            snapshot = args.snapshot,
            snapshotFormat = SnapshotFormat.COMPACT,
            snapshotOptions =
                SnapshotOptions(
                    windowId = args.window?.let(::windowId),
                    maxNodes = args.maxNodes,
                    maxChars = args.maxChars,
                    textOnly = args.textOnly,
                    redact = args.redact,
                ),
        )
    requireWaitAccess(wait, scopes)
    val result = services.waits.wait(wait, scopes)
    val header =
        "matched=${result.matched} timedOut=${result.timedOut} elapsedMs=${result.elapsedMs}"
    val body = result.snapshot?.compact
    val text = TextContent(text = if (body.isNullOrBlank()) header else "$header\n$body")
    if (args.screenshot) listOf(text, screenshotContent(services, args.scale)) else listOf(text)
}

internal suspend fun sessionStart(services: ServerServices): CallToolResult = runTool {
    val id = checkNotNull(services.sessions).start()
    "session=$id\nPass session=$id on every phone call for this task, reads included. A call " +
        "without it acts as you, and tabs opened without it are yours, not the task's. End it " +
        "with session_end when the task is done."
}

internal suspend fun sessionEnd(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val id = request.args<SessionEndArgs>().session
    if (!checkNotNull(services.sessions).end(id)) {
        throw ApiException.notFound("No session you started has that id; it may have ended")
    }
    "Ended the session: its tabs are closed, and the device's release cleanup policy " +
        "applies if it held the screen."
}

/** Lets the session's screen go, with cleanup, keeping the session and its tabs. */
internal suspend fun screenRelease(services: ServerServices): CallToolResult = runTool {
    val holder = services.holder ?: return@runTool "This call has no MCP session."
    val held = services.lease.holds(holder)
    services.lease.release(holder)
    if (held) {
        "Released the screen. PhoneAPI applies the device owner’s configured cleanup policy " +
            "before the next client gets it. Your tabs stay yours, and your next call that needs " +
            "the screen takes it again, waiting if someone else has it."
    } else {
        "This session did not hold the screen."
    }
}

internal suspend fun keyboardHide(services: ServerServices): CallToolResult = runTool {
    jsonText(services.input.hideIme())
}

internal suspend fun keyboardShow(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.input.showIme(request.args<ImeShowRequest>()))
}

internal suspend fun appLaunch(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        val args = request.args<PackageArgs>()
        jsonText(
            services.apps.launch(
                args.packageName,
                LaunchRequest(fresh = args.fresh, activity = args.activity, wait = args.wait),
            )
        )
    }

internal suspend fun appStop(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.apps.stop(request.args<PackageNameArgs>().packageName))
    }

internal suspend fun appClear(services: ServerServices, request: CallToolRequest): CallToolResult =
    runTool {
        jsonText(services.apps.clear(request.args<PackageNameArgs>().packageName))
    }

internal suspend fun openIntent(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.apps.intent(request.args<IntentRequest>()))
}

internal suspend fun deviceWake(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.wake())
}

internal suspend fun deviceUnlock(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.unlock(UnlockRequest()))
}

internal suspend fun deviceLock(services: ServerServices): CallToolResult = runTool {
    jsonText(services.power.lock())
}

internal suspend fun deviceOrientation(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.power.orientation(request.args<OrientationRequest>()))
}

internal suspend fun browserTargets(services: ServerServices): CallToolResult = runTool {
    jsonText(services.browser.targets())
}

internal suspend fun browserOpen(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserOpenArgs>()
    jsonText(services.browser.openTab(args.url, args.capture))
}

internal suspend fun browserCapture(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserCaptureArgs>()
    jsonText(services.browser.capture(args.target, args.on))
}

internal suspend fun browserNavigate(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<TargetUrlArgs>()
    jsonText(services.browser.navigate(args.target, args.url))
}

internal suspend fun browserClose(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    jsonText(services.browser.closeTab(request.args<TargetArgs>().target))
}

internal suspend fun browserSnapshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserSnapshotArgs>()
    val snapshot =
        services.browser.snapshot(
            args.target,
            BrowserSnapshotOptions(args.maxNodes, args.maxChars, args.show),
        )
    snapshotText(snapshot, args.show)
}

/** The outline, with notes on whether the tab was drawn after its header line. */
internal fun snapshotText(snapshot: BrowserSnapshot, show: Boolean = false): String {
    val notes =
        listOfNotNull(RELOADED_NOTE.takeIf { snapshot.reloaded }) +
            drawnNotes(snapshot.shown, snapshot.pending, sparse = false, show, "snapshot")
    if (notes.isEmpty()) return snapshot.compact
    val header = snapshot.compact.substringBefore('\n')
    val rest = snapshot.compact.substringAfter('\n', "")
    return (listOf(header) + notes + listOfNotNull(rest.ifEmpty { null })).joinToString("\n")
}

internal suspend fun browserTap(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserTapArgs>()
    jsonText(
        services.browser.tap(
            args.target,
            BrowserTapRequest(
                ref = args.ref,
                selector = args.selector,
                humanize = args.humanize,
                autoWake = args.autoWake,
                input = args.input,
                waitMs = args.waitMs,
            ),
        )
    )
}

internal suspend fun browserSwipe(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserSwipeArgs>()
    jsonText(
        services.browser.swipe(
            args.target,
            BrowserSwipeRequest(
                from = args.from,
                to = args.to,
                ref = args.ref,
                selector = args.selector,
                direction = args.direction,
                distance = args.distance,
                durationMs = args.durationMs,
                fling = args.fling,
                humanize = args.humanize,
                autoWake = args.autoWake,
                input = args.input,
            ),
        )
    )
}

internal suspend fun browserGesture(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserGestureArgs>()
    jsonText(
        services.browser.gesture(
            args.target,
            BrowserGestureRequest(
                pointers = args.pointers,
                humanize = args.humanize,
                autoWake = args.autoWake,
                input = args.input,
            ),
        )
    )
}

internal suspend fun browserKey(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserKeyArgs>()
    jsonText(
        services.browser.key(
            args.target,
            BrowserKeyRequest(
                key = args.key,
                longPress = args.longPress,
                metaState = args.metaState,
                autoWake = args.autoWake,
                input = args.input,
            ),
        )
    )
}

internal suspend fun browserText(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserTextArgs>()
    jsonText(
        services.browser.text(
            args.target,
            BrowserTextRequest(
                text = args.text,
                ref = args.ref,
                selector = args.selector,
                mode = args.mode,
                clear = args.clear,
                submit = args.submit,
                minDelayMs = args.minDelayMs,
                maxDelayMs = args.maxDelayMs,
                autoWake = args.autoWake,
                input = args.input,
            ),
        )
    )
}

internal suspend fun browserEval(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<EvalArgs>()
    jsonText(
        services.browser.evaluate(args.target, EvalRequest(args.expression, args.awaitPromise))
    )
}

internal suspend fun browserConsole(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<ConsoleArgs>()
    jsonText(services.browser.console(args.target, ConsoleRequest(args.timeoutMs)))
}

internal suspend fun screenshot(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runContent {
    listOf(screenshotContent(services, request.args<ScaleArgs>().scale))
}

private suspend fun screenshotContent(services: ServerServices, scale: Float): ImageContent {
    val png = services.screenshots(scale)
    if (png.size > MAX_IMAGE_BYTES) {
        throw ApiException.badRequest("Screenshot is too large; pass a smaller scale")
    }
    return ImageContent(data = Base64.getEncoder().encodeToString(png), mimeType = "image/png")
}

internal suspend fun logcatTail(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<LogcatArgs>()
    if (args.lines !in 1..MAX_LOG_LINES) {
        throw ApiException.badRequest("lines must be 1..$MAX_LOG_LINES")
    }
    val tag = args.tag
    if (tag != null) checkLogTag(tag, "tag")
    // `-t N` counts raw buffer lines, then the tag filter runs, so a tag plus a small N
    // returns nothing. Dump the tag and keep the last N matching lines instead.
    val argv =
        if (tag == null) {
            listOf("logcat", "-d", "-t", args.lines.toString())
        } else {
            listOf("logcat", "-d", "-s", "$tag:I")
        }
    val result = services.shell(argv)
    if (!result.ok) {
        val detail = result.failureMessage().ifBlank { "logcat exited ${result.exit}" }
        throw ApiException.unavailable("helper_error", detail)
    }
    if (tag == null) result.stdout else tailLines(result.stdout, args.lines)
}

private fun tailLines(stdout: String, lines: Int): String =
    stdout
        .lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("---------") }
        .toList()
        .takeLast(lines)
        .joinToString("\n")

internal suspend fun runTool(block: suspend () -> String): CallToolResult = runContent {
    listOf(TextContent(text = block()))
}

/** Runs [block], turning API and argument errors into tool errors. */
internal suspend fun runContent(block: suspend () -> List<ContentBlock>): CallToolResult =
    try {
        CallToolResult(content = block(), isError = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        toolFailure(toolError(e))
    } catch (e: SerializationException) {
        toolFailure("bad_request: ${e.message.orEmpty()}")
    }

private fun toolFailure(text: String): CallToolResult =
    CallToolResult(content = listOf(TextContent(text = text)), isError = true)

private fun toolError(error: ApiException): String {
    val detail = error.message?.takeIf { it.isNotBlank() && it != error.error }
    return if (detail == null) error.error else "${error.error}: $detail"
}

internal inline fun <reified T> jsonText(value: T): String =
    ApiJson.encodeToString(serializer<T>(), value)

internal inline fun <reified T> CallToolRequest.args(): T {
    val arguments = this.arguments ?: JsonObject(emptyMap())
    return ApiJson.decodeFromJsonElement(serializer<T>(), arguments)
}

@Serializable
internal data class SnapshotArgs(
    @Doc("Show only this window, such as 3 or w3. Omit it for the default set of windows.")
    val window: String? = null,
    @Doc("Stop this many levels down. Leaving nodes out marks the outline truncated.")
    val maxDepth: Int? = null,
    @Doc(
        "Most nodes to show. Defaults to 400. The rest are counted, and a line after the header says so."
    )
    val maxNodes: Int = DEFAULT_SNAPSHOT_NODES,
    @Doc("Most characters of outline to show, cut at a line. Cuts are reported like maxNodes.")
    val maxChars: Int? = null,
    @Doc("Leave out positions, resource ids, states, and actions, for reading rather than acting.")
    val textOnly: Boolean = false,
    @Doc(
        "Mask the text of password and one-time-code fields, and anything that looks like a card number. The first line then counts them as redacted=N. Defaults to true. A screenshot still shows them."
    )
    val redact: Boolean = true,
    @Doc("Also include nodes that are off screen or hidden.") val includeInvisible: Boolean = false,
    @Doc("Include every window, such as the status bar and the keyboard's contents.")
    val all: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable
internal data class UiFindArgs(
    val selector: NodeSelector,
    @Doc("Most matches to return. Defaults to 20.") val limit: Int = 20,
    @Doc("Also match nodes that are off screen or hidden.") val includeInvisible: Boolean = false,
    @Doc(
        "Mask the text of password and one-time-code fields, and card numbers, as ui_snapshot does. redacted counts them. Defaults to true."
    )
    val redact: Boolean = true,
)

@Serializable
internal data class NodeActArgs(
    @Doc("Node ref from ui_snapshot or ui_find, such as e12.") val ref: String,
    @Doc(
        "Standard action such as click, longClick, setText, scrollForward or imeEnter, or a custom action label listed on the node in the snapshot."
    )
    val action: String,
    @Doc("real uses a touch where it can. semantic calls performAction and generates no touch.")
    val mode: ActionMode = ActionMode.REAL,
    val text: String? = null,
    val force: Boolean = false,
    val autoWake: Boolean = true,
)

@Serializable internal data class PackageNameArgs(@SerialName("package") val packageName: String)

@Serializable
internal data class PackageArgs(
    @SerialName("package") val packageName: String,
    val fresh: Boolean = false,
    val activity: String? = null,
    val wait: Boolean = true,
)

@Serializable
internal data class BrowserOpenArgs(
    val url: String,
    @Doc(CAPTURE_OPEN_DOC) val capture: Boolean = false,
)

@Serializable
internal data class BrowserCaptureArgs(
    val target: String,
    @Doc(
        "true (default) starts recording, or keeps it going. false stops it and forgets what it recorded."
    )
    val on: Boolean = true,
)

@Serializable internal data class TargetArgs(val target: String)

@Serializable
internal data class BrowserSnapshotArgs(
    val target: String,
    @Doc("Most nodes to show, 1 to 5000. Defaults to 400. A line after the header counts the rest.")
    val maxNodes: Int = 400,
    @Doc("Most characters of outline to show, cut at a line. Cuts are reported like maxNodes.")
    val maxChars: Int? = null,
    @Doc(SHOW_DOC) val show: Boolean = false,
)

@Serializable internal data class TargetUrlArgs(val target: String, val url: String)

@Serializable
internal data class BrowserTapArgs(
    val target: String,
    val ref: String? = null,
    val selector: String? = null,
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
    @Doc(TAP_WAIT_DOC) val waitMs: Long = DEFAULT_TAP_WAIT_MS,
)

@Serializable
internal data class BrowserSwipeArgs(
    val target: String,
    val from: Point? = null,
    val to: Point? = null,
    val ref: String? = null,
    val selector: String? = null,
    val direction: SwipeDirection? = null,
    val distance: Float = 0.6f,
    val durationMs: Long = 300,
    val fling: Boolean = true,
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

@Serializable
internal data class BrowserGestureArgs(
    val target: String,
    val pointers: List<List<TimedPoint>>,
    @Doc("Jitter the path and vary the finger contact. Defaults to true.")
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

@Serializable
internal data class BrowserKeyArgs(
    val target: String,
    val key: String,
    val longPress: Boolean = false,
    val metaState: Int = 0,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

@Serializable
internal data class BrowserTextArgs(
    val target: String,
    val text: String,
    val ref: String? = null,
    val selector: String? = null,
    val mode: TextMode = TextMode.AUTO,
    val clear: Boolean = false,
    val submit: Boolean = false,
    val minDelayMs: Long = 40,
    val maxDelayMs: Long = 140,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

@Serializable
internal data class EvalArgs(
    val target: String,
    val expression: String,
    val awaitPromise: Boolean = false,
)

@Serializable internal data class ConsoleArgs(val target: String, val timeoutMs: Long = 1_000)

@Serializable
internal data class ScaleArgs(
    @Doc("Scale from 0.1 to 1. Defaults to 0.5, which is smaller than the REST default.")
    val scale: Float = ScreenshotScale.MCP
)

@Serializable internal data class LogcatArgs(val lines: Int = 80, val tag: String? = null)

@Serializable internal class EmptyArgs

@Serializable
internal data class SessionEndArgs(@Doc("The id session_start returned.") val session: String)

@Serializable
internal data class WaitArgs(
    @Doc(
        "Every condition must match. Each object's type is node, window, ime, idle, screen, keyguard, or browser.*. all and any together need at least one condition."
    )
    val all: List<WaitCondition> = emptyList(),
    @Doc("At least one condition must match. Same types as all.")
    val any: List<WaitCondition> = emptyList(),
    @Doc(
        "The browser tab for every browser.* condition that names none, so it need not be repeated on each."
    )
    val target: String? = null,
    @Doc("Give up after this many milliseconds.") val timeoutMs: Long = 10_000,
    @Doc("After a match, keep waiting until the UI is quiet for this many milliseconds.")
    val settleMs: Long = 0,
    @Doc(
        "Include a fresh ui_snapshot outline in the result, so one call both waits for an action's result and shows it. Cut and masked as ui_snapshot is, by the arguments below."
    )
    val snapshot: Boolean = false,
    @Doc(
        "Include a screenshot in the result, for maps, Street View, photos, and other screens whose tree is empty."
    )
    val screenshot: Boolean = false,
    @Doc("Screenshot scale from 0.1 to 1. Defaults to 0.5.") val scale: Float = ScreenshotScale.MCP,
    @Doc("Snapshot only this window, such as 3 or w3.") val window: String? = null,
    @Doc("Most snapshot nodes to show. Defaults to 400. A line after the header counts the rest.")
    val maxNodes: Int = DEFAULT_SNAPSHOT_NODES,
    @Doc("Most characters of snapshot to show, cut at a line.") val maxChars: Int? = null,
    @Doc("Leave positions, resource ids, states, and actions out of the snapshot.")
    val textOnly: Boolean = false,
    @Doc("Mask secrets in the snapshot as ui_snapshot does. Defaults to true.")
    val redact: Boolean = true,
)

/** The most nodes an MCP snapshot shows unless asked for more, as for browser_snapshot. */
private const val DEFAULT_SNAPSHOT_NODES = 400
private const val MAX_IMAGE_BYTES = 1_500_000
private const val MAX_LOG_LINES = 400
