package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.ClipboardReadRequest
import net.die.phoneapi.model.ClipboardWriteRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.schema.JsonSchemas
import net.die.phoneapi.server.ServerServices

internal data class McpTool(
    val name: String,
    val description: String,
    val scope: Scope,
    val available: (Capabilities) -> Boolean,
    val arguments: KSerializer<*>,
    val schema: ToolSchema = toolSchema(arguments),
    val annotations: ToolAnnotations? = null,
    val call: suspend (ServerServices, Set<Scope>, CallToolRequest) -> CallToolResult,
)

internal fun visibleMcpTools(scopes: Set<Scope>, capabilities: Capabilities): List<McpTool> =
    MCP_TOOLS.filter { it.scope in scopes && it.available(capabilities) }
        .map { tool ->
            val note = reducedNote(tool.name, capabilities)
            if (note == null) tool else tool.copy(description = "${tool.description} $note")
        }

private val readOnly = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

private val changes =
    ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false)

private val destructive =
    ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false)

private val always: (Capabilities) -> Boolean = { true }

private fun reducedNote(name: String, caps: Capabilities): String? =
    if (!caps.uiStableIds && name in REF_TOOLS) "Reduced mode: node ids are not stable." else null

private val REF_TOOLS =
    setOf(
        "ui_snapshot",
        "ui_find",
        "ui_act",
        "tap",
        "swipe",
        "type_text",
        "wait_for",
        "keyboard_show",
    )

private fun toolSchema(serializer: KSerializer<*>): ToolSchema {
    val schemas = JsonSchemas(refPrefix = "#/\$defs/")
    val root = schemas.inline(serializer.descriptor)
    val required = root["required"]?.jsonArray?.map { it.jsonPrimitive.content }
    return ToolSchema(
        properties = root["properties"]?.jsonObject ?: JsonObject(emptyMap()),
        required = required?.takeIf { it.isNotEmpty() },
        defs = schemas.definitions.takeIf { it.isNotEmpty() }?.let { JsonObject(it) },
    )
}

/** The tools that manage sessions, which take no `session` argument of their own. */
internal val SESSION_TOOLS = setOf("session_start", "session_end")

private val TOOLS: List<McpTool> =
    listOf(
        McpTool(
            name = "device_info",
            description =
                "Model, Android version, display, helper status, capabilities, and current state: screen, keyguard, keyboard, and foregroundPackage. Cheap. foregroundPackage only updates while the helper is watching the screen, as during wait_for or right after ui_snapshot, so it can be missing or stale. To know what is in front, take a ui_snapshot or wait_for a window condition. The phone is shared: screenHolder says who has the screen now (you; parent, the session that started yours; subagent, one you started with session_start; agent; person; or script; absent when it is free), and screenHeldMs for how long. Tools whose descriptions say Uses the screen wait for it.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = readOnly,
            call = { graph, _, _ -> deviceInfo(graph) },
        ),
        McpTool(
            name = "session_start",
            description =
                "Give a subagent or parallel task its own place in line for the screen and its own browser tabs, so parallel tasks do not tap over each other. Not needed for one agent working step by step. Call it once per task, either yourself, putting the id in the subagent's instructions, or in the subagent. Then pass the id as session on every phone call for that task, reads included; a call without session acts as you, with your screen turn and your tabs. Tabs are not shared between sessions. End it with session_end when the task is done; it also ends when your connection ends, or after 20 minutes unused.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> sessionStart(graph) },
        ),
        McpTool(
            name = "session_end",
            description =
                "End a session from session_start when its task is done: close the tabs it opened, give back the ones it took over, and let the screen go if it held it.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<SessionEndArgs>(),
            annotations = destructive,
            call = { graph, _, request -> sessionEnd(graph, request) },
        ),
        McpTool(
            name = "screen_release",
            description =
                "Let the screen go when you are done with it for now, so other clients can use the phone. PhoneAPI applies the device owner’s configured release cleanup (home-lock by default). Your tabs stay yours and background browser work keeps going. The next call that needs the screen takes it again, waiting if someone else has it. Holding the screen idle while another client waits also lets it go, after 30 s.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> screenRelease(graph) },
        ),
        McpTool(
            name = "ui_snapshot",
            description =
                "Compact outline of the visible nodes on screen, with refs for ui_act. Covers app windows, plus system windows such as dialogs and the notification shade when they are focused or large. The keyboard is listed without its contents. Use it when you do not know what is on screen. To act on something whose label you know, tap or type_text with a text selector, with no snapshot first. To see what an action did, use wait_for with snapshot true rather than calling this again. Shows up to maxNodes lines (default 400); when anything is left out, the first line ends with truncated and the next lines say how much, so narrow with window or ui_find, or raise maxNodes. textOnly drops positions, ids, states, and actions. Password and one-time-code fields and card numbers are masked unless redact is false. Canvas content such as maps and Street View shows little, so use a screenshot there.",
            scope = Scope.OBSERVE,
            available = Capabilities::uiSnapshot,
            arguments = serializer<SnapshotArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> uiSnapshot(graph, request) },
        ),
        McpTool(
            name = "ui_find",
            description =
                "Find nodes by selector and return each match's bounds and actions. Much smaller than ui_snapshot when you know what you want. Use index when several nodes match. Secrets are masked as in ui_snapshot unless redact is false.",
            scope = Scope.OBSERVE,
            available = Capabilities::uiSnapshot,
            arguments = serializer<UiFindArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> uiFind(graph, request) },
        ),
        McpTool(
            name = "ui_act",
            description =
                "Act on a ref from the latest ui_snapshot or ui_find. action is click, longClick, setText, scrollForward, imeEnter, or a custom action listed on the node. mode semantic skips real touch.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<NodeActArgs>(),
            annotations = changes,
            call = { graph, _, request -> uiAct(graph, request) },
        ),
        McpTool(
            name = "tap",
            description =
                "Tap a node selector or a point in screen pixels. A selector by text, id, or desc needs no snapshot first, so prefer it when you know the label; a ref needs one. Humanized by default. Wakes the screen itself. Follow with wait_for on what should appear, with snapshot true to see it. Uses the screen.",
            scope = Scope.CONTROL,
            available = Capabilities::inputInject,
            arguments = serializer<TapRequest>(),
            annotations = changes,
            call = { graph, _, request -> tap(graph, request) },
        ),
        McpTool(
            name = "swipe",
            description =
                """Swipe from one point to another, or in a direction (up, down, left, right) across the screen or inside a selector. from and to are objects such as {"x":540,"y":1800}. Uses the screen.""",
            scope = Scope.CONTROL,
            available = Capabilities::inputInject,
            arguments = serializer<SwipeRequest>(),
            annotations = changes,
            call = { graph, _, request -> swipe(graph, request) },
        ),
        McpTool(
            name = "type_text",
            description =
                "Type into the focused field, or into selector first, so a text or id selector needs no tap or snapshot before it. mode auto taps the on-screen keyboard and falls back to setText. setText is fastest for long text. clear replaces the field's text. submit presses the IME action. no_editor means no editable field has focus.",
            scope = Scope.CONTROL,
            available = Capabilities::textKeyevent,
            arguments = serializer<TextRequest>(),
            annotations = changes,
            call = { graph, _, request -> typeText(graph, request) },
        ),
        McpTool(
            name = "press_key",
            description =
                "Press BACK, HOME, ENTER, DEL, or any KEYCODE_* name. HOME returns to the launcher. Uses the screen.",
            scope = Scope.CONTROL,
            available = Capabilities::textKeyevent,
            arguments = serializer<KeyRequest>(),
            annotations = changes,
            call = { graph, _, request -> pressKey(graph, request) },
        ),
        McpTool(
            name = "clipboard_read",
            description =
                "Read the phone's clipboard as text, with its MIME types, or as an image when it holds one. copy true presses KEYCODE_COPY first, so the app in front copies what is selected on screen, and changed says whether the clipboard changed. A selection made by script in a background tab is not on screen, so it is not copied. The clipboard is the phone user's and can hold a password (sensitive true): read it only for the task at hand. Uses the screen. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::clipboard,
            arguments = serializer<ClipboardReadRequest>(),
            annotations = changes,
            call = { graph, _, request -> clipboardRead(graph, request) },
        ),
        McpTool(
            name = "clipboard_write",
            description =
                "Put plain text on the phone's clipboard, replacing what the phone user copied. paste true then presses KEYCODE_PASTE into the focused field, which is faster than type_text for long text, though the app sees a paste rather than typing. Uses the screen. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::clipboard,
            arguments = serializer<ClipboardWriteRequest>(),
            annotations = destructive,
            call = { graph, _, request -> clipboardWrite(graph, request) },
        ),
        McpTool(
            name = "wait_for",
            description =
                "Wait until all or any conditions match, then optionally show the result: snapshot true adds a ui_snapshot outline, and screenshot true adds a screenshot for maps and other screens with an empty tree. So an action takes two calls, the action and one wait_for, rather than a snapshot before and after. Pass at least one condition. Use it after an action instead of sleeping. A condition's type is node, window, ime, idle, screen, keyguard, or browser.*. After open_intent or app_launch, wait for {type window, package} with {type node, selector {textContains}}. After browser_open, wait for browser.url and browser.settled on its tab: pass the tab as target, once at the top level or on each browser condition. To check a browser_tap, wait for browser.element with a CSS selector for the new state, such as an attribute selector, rather than running browser_eval. idle is a fallback when no text is known. Browser conditions need the browser scope, and target when more than one page is open.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<WaitArgs>(),
            annotations = readOnly,
            call = { graph, scopes, request -> waitFor(graph, scopes, request) },
        ),
        McpTool(
            name = "keyboard_hide",
            description = "Hide the soft keyboard.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> keyboardHide(graph) },
        ),
        McpTool(
            name = "keyboard_show",
            description =
                "Show the soft keyboard for an editable node. Omit selector to use the focused field.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<ImeShowRequest>(),
            annotations = changes,
            call = { graph, _, request -> keyboardShow(graph, request) },
        ),
        McpTool(
            name = "app_launch",
            description =
                "Launch an app by package name and bring it to the front. fresh clears its task first. wait (default true) waits for it to reach the foreground, not for its content. Uses the screen.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<PackageArgs>(),
            annotations = changes,
            call = { graph, _, request -> appLaunch(graph, request) },
        ),
        McpTool(
            name = "app_stop",
            description =
                "Force-stop an app. Unsaved state in it is lost. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<PackageNameArgs>(),
            annotations = destructive,
            call = { graph, _, request -> appStop(graph, request) },
        ),
        McpTool(
            name = "app_clear",
            description =
                "Erase an app's data, including sign-in. Not reversible. Use only when the user asked. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<PackageNameArgs>(),
            annotations = destructive,
            call = { graph, _, request -> appClear(graph, request) },
        ),
        McpTool(
            name = "open_intent",
            description =
                "Start an intent, such as action android.intent.action.VIEW with an https URL as data, to open a place in Maps or a deep link in an app. For a web page in Chrome, use browser_open instead: it returns the target id that browser tools need. package and component are optional. Waits for the app to reach the foreground, not for its content, so follow with wait_for, with snapshot or screenshot true to see the content. Without a package, a URI that several apps handle shows a chooser and returns ok false: name a package. Wakes the screen itself. Uses the screen. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<IntentRequest>(),
            annotations = changes,
            call = { graph, _, request -> openIntent(graph, request) },
        ),
        McpTool(
            name = "device_wake",
            description =
                "Turn the screen on. Leaves the keyguard up. Not needed before other calls: actions, snapshots, and intents wake the screen themselves.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> deviceWake(graph) },
        ),
        McpTool(
            name = "device_unlock",
            description =
                "Dismiss the keyguard. A secure lock uses the stored PIN. needs_user means there is no stored PIN, or the lock is a pattern or password.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> deviceUnlock(graph) },
        ),
        McpTool(
            name = "device_lock",
            description =
                "Turn the screen off and lock. The foreground app stays, so press HOME first for a neutral screen. Background browser work keeps running.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = destructive,
            call = { graph, _, _ -> deviceLock(graph) },
        ),
        McpTool(
            name = "device_orientation",
            description =
                "Set screen orientation. auto follows the sensor. lock freezes the current rotation. 0, 90, 180, and 270 lock to that rotation from the device's natural orientation. An app can still force its own orientation.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<OrientationRequest>(),
            annotations = changes,
            call = { graph, _, request -> deviceOrientation(graph, request) },
        ),
        McpTool(
            name = "browser_targets",
            description =
                "List your Chrome tabs (owned true) and tabs no session owns (owned false), with id, title, and url, plus the debuggable WebViews of the app in front while you hold the screen. Other sessions' tabs are not listed, and a session's tabs close when it ends. Chrome updates the list itself, so a closed tab or a redirect can lag behind. Chrome must be running with USB debugging on. Android 11+ needs PhoneAPI paired with wireless debugging. Android 10 needs the USB DevTools forward.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<EmptyArgs>(),
            annotations = readOnly,
            call = { graph, _, _ -> browserTargets(graph) },
        ),
        McpTool(
            name = "browser_open",
            description =
                "Open a tab and return its target, for every other browser tool. The tab is yours: other sessions cannot see it, and it closes when your session ends. Prefer it over open_intent for web pages. With Chrome hidden, the tab opens in the background and the screen is untouched. With Chrome on screen, Chrome switches to the new tab, and the result has tookScreen true. url must be http(s) or about:blank. A page that hands off to an installed app loads as a website in the tab. Follow with wait_for on browser.url and browser.settled. To only read or search a page, browser_read or browser_find with url opens it for you.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserOpenArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserOpen(graph, request) },
        ),
        McpTool(
            name = "browser_navigate",
            description =
                "Navigate an existing target to url, in the background. target is the id from browser_targets or browser_open. Follow with wait_for.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetUrlArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserNavigate(graph, request) },
        ),
        McpTool(
            name = "browser_close",
            description =
                "Close a tab by target id, in the background. Works after a redirect, where window.close() does not.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserClose(graph, request) },
        ),
        McpTool(
            name = "browser_snapshot",
            description =
                "Compact outline of a browser target's accessibility tree, with refs for browser_tap. Works in the background. Shows up to maxNodes lines (default 400) and 40 levels. When anything is left out, the first line ends with truncated and the next lines say how much. Links show their text, not their URL. To read a page's text, browser_read is much smaller. A tab that is not on screen is not drawn, so content that loads when drawn may be missing; the header says so, and show brings the tab forward first.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserSnapshotArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserSnapshot(graph, request) },
        ),
        McpTool(
            name = "browser_read",
            description =
                "Read a page's text as markdown, a slice at a time. Works in the background. scope article (default) is the main content without navigation, footers, and ads. page is all visible text. selector reads every element it matches, in page order, such as a table. The header gives the total length, a version, and nextOffset for the next slice, and flags a page that looks blocked or still loading. It also names the page's feeds, its canonical URL, and its next page when it links to them. Pass version from an earlier read to get only the header when the content has not changed. Pages built by scripts often embed their data as JSON, which the header's data line names. Read it with scope data: without a path you get an outline whose lines each start with a path to pass back; path reads one value; a * in a path reads that field from every item, such as __NEXT_DATA__/props/pageProps/items/*/name; and paths reads several values in one call. Prefer these to paging through a large blob. Data a page fetches after it loads is not embedded; browser_capture records those responses, and a scope data read with url records them for you. outline returns only headings with their offsets, cheaply. Pass url instead of target to open a new tab and read it in one call, and close true to close the tab afterwards, so a one-look read is a single call. A tab that is not on screen is not drawn, so content that loads when drawn may be missing; the header says so, and show brings the tab forward first.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserReadArgs>(),
            annotations = changes,
            call = { graph, scopes, request -> browserRead(graph, scopes, request) },
        ),
        McpTool(
            name = "browser_find",
            description =
                "Find text or links in a page's content, with offsets browser_read takes when given the same scope, selector, and format. Works in the background. kind text returns each match with context and its nearest heading. To look for several things, or for something whose wording you can only guess, pass queries: up to 20, each searched on its own, with a no-matches line for the ones not there. Listing likely wordings and synonyms in one call costs far less than a find per guess. Matching is a plain substring that ignores case, so list wordings, not capitalizations, and use regex with \\b for whole words. kind links returns each link's text, URL, and whether it is same-origin, filtered by query and hrefContains, each URL once and without tracking parameters. With scope data it searches the page's embedded JSON and gives each match's path, which browser_read's path takes; path, * allowed, limits the search to part of it. Pass url instead of target to open the page in a new tab first, and close true to close the tab afterwards.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserFindArgs>(),
            annotations = changes,
            call = { graph, scopes, request -> browserFind(graph, scopes, request) },
        ),
        McpTool(
            name = "browser_tap",
            description =
                "Tap a snapshot ref or a CSS selector in a browser target. A selector waits up to waitMs (default 5000) for its element to appear, and the element is scrolled into view, so no wait or scroll is needed first. To check the tap worked, wait_for browser.element with a selector for the new state. The default input auto is touch while you hold the screen, and cdp-mouse otherwise, so tapping a background tab does not take a free screen. input touch brings the tab to the front and uses the screen. input cdp sends touch events over DevTools without bringing the tab forward, but Chrome only takes them on the tab it is showing, with Chrome on screen. input cdp-mouse clicks with a mouse, which works with Chrome hidden and the screen off, but the page sees a mouse rather than a finger. All three are trusted input, unlike a click from browser_eval.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTapArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserTap(graph, request) },
        ),
        McpTool(
            name = "browser_swipe",
            description =
                "Swipe a browser target. from and to are CSS viewport pixels, or pass a direction. The default input auto is touch, which brings the tab to the front and uses the screen. input cdp swipes over DevTools without bringing the tab forward, but only on the tab Chrome is showing, with Chrome on screen. With Chrome hidden there is no trusted way to scroll, since Chrome holds wheel and scroll input too. Scrolling from browser_eval works but is untrusted.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserSwipeArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserSwipe(graph, request) },
        ),
        McpTool(
            name = "browser_gesture",
            description =
                "Play pointer paths on a browser target, in CSS viewport pixels. The default input auto is touch, which brings the tab to the front and uses the screen. input cdp plays them over DevTools without bringing the tab forward, but only on the tab Chrome is showing, with Chrome on screen.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserGestureArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserGesture(graph, request) },
        ),
        McpTool(
            name = "browser_key",
            description =
                "Press a key on a browser target. The default input auto is touch while you hold the screen, and cdp otherwise. input touch brings the tab to the front and injects a hardware key. input cdp sends it to the target in the background. Device keys such as HOME need input touch.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserKeyArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserKey(graph, request) },
        ),
        McpTool(
            name = "browser_type",
            description =
                "Type text into a field in a browser target. ref or selector focuses the field first. The default input auto is touch while you hold the screen, and cdp otherwise. input touch brings the tab to the front and uses the hardware text modes. input cdp inserts the text in the background.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTextArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserText(graph, request) },
        ),
        McpTool(
            name = "browser_eval",
            description =
                "Evaluate JavaScript in an isolated world and return the result. Works in the background. awaitPromise waits for a promise. A script exception is a result field. Not retried, so a script with side effects runs at most once. Clicks and scrolls made from a script are untrusted (isTrusted false), which bot detection can see, so click with browser_tap instead.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<EvalArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserEval(graph, request) },
        ),
        McpTool(
            name = "browser_capture",
            description =
                "Record the JSON responses a tab's page fetches, such as the data a page loads after it appears or when you tap something. A response counts when its content type says JSON, whatever its request method. Turn it on before the action, then browser_read with scope data lists each response as response-1, response-2, and so on, which paths read like any other blob. A body of several JSON values one after another reads as an array of them. A body that does not parse as JSON is listed with the reason, but cannot be read. Recording lasts until on false, the tab closes, or 10 minutes pass without a data read or a call to this. To record a page's load, browser_open with capture true, or call this before browser_navigate. browser_read with url and scope data records for you. Chrome keeps the last 4 MB of what the page loads, so on a busy page read soon after the action.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserCaptureArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserCapture(graph, request) },
        ),
        McpTool(
            name = "browser_console",
            description =
                "Collect log entries for timeoutMs. Entries from before the call are not returned, so start it before the action you want to watch.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<ConsoleArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> browserConsole(graph, request) },
        ),
        McpTool(
            name = "screenshot",
            description =
                "PNG of the screen. Prefer ui_snapshot, and use this when the tree is empty or unhelpful, such as maps and images. After an action, wait_for with screenshot true waits and shows the screen in one call. scale is 0.1 to 1, default 0.5. Divide image coordinates by scale to get tap coordinates.",
            scope = Scope.OBSERVE,
            available = Capabilities::screenshotHelper,
            arguments = serializer<ScaleArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> screenshot(graph, request) },
        ),
        McpTool(
            name = "logcat_tail",
            description = "Recent log lines from the shell helper. lines is 1 to 400, default 80.",
            scope = Scope.OBSERVE,
            available = Capabilities::logcatAll,
            arguments = serializer<LogcatArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> logcatTail(graph, request) },
        ),
    )

/** Every tool but the session tools also takes `session`. */
internal val MCP_TOOLS: List<McpTool> = TOOLS.map {
    if (it.name in SESSION_TOOLS) it else it.withSessionArgument()
}

/** The argument that names which session a call is for. */
internal const val SESSION_ARGUMENT = "session"

private const val SESSION_DOC =
    "Id from session_start, if this call is for a subagent or parallel task. Use the same id on every call for that task; leave it out for your own work."

private fun McpTool.withSessionArgument(): McpTool {
    val argument = buildJsonObject {
        put("type", "string")
        put("description", SESSION_DOC)
    }
    val properties = JsonObject(schema.properties.orEmpty() + (SESSION_ARGUMENT to argument))
    return copy(schema = schema.copy(properties = properties))
}

internal data class McpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val read: (ServerServices) -> String,
)

internal fun mcpResources(scopes: Set<Scope>): List<McpResource> =
    if (Scope.OBSERVE !in scopes) emptyList() else MCP_RESOURCES

private val MCP_RESOURCES =
    listOf(
        McpResource(
            uri = "phoneapi://device/capabilities",
            name = "capabilities",
            description = "Which device features are available right now.",
            mimeType = "application/json",
            read = { services -> jsonText(services.device.capabilities()) },
        ),
        McpResource(
            uri = "phoneapi://viewer",
            name = "viewer",
            description =
                "WebCodecs page at /viewer. Pass the bearer token as access_token. That URL sets a cookie and redirects without the token. WebSocket upgrades also accept the query parameter.",
            mimeType = "text/plain",
            read = { services -> services.viewerText() },
        ),
    )
