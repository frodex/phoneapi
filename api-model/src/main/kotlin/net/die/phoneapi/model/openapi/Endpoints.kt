package net.die.phoneapi.model.openapi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.CreateTokenRequest
import net.die.phoneapi.model.CreatedToken
import net.die.phoneapi.model.DeviceInfo
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NavigateRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.OpenTabRequest
import net.die.phoneapi.model.PinStatus
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SetPinRequest
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TokenInfo
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

public enum class ApiMethod {
    GET,
    POST,
    PUT,
    DELETE,
    WEBSOCKET,
}

/**
 * One REST or WebSocket route. [request] and [response] are null when that side has no JSON body.
 */
public data class ApiEndpoint(
    public val method: ApiMethod,
    public val path: String,
    public val scope: Scope,
    public val summary: String,
    public val request: KSerializer<*>? = null,
    public val response: KSerializer<*>? = null,
    public val status: Int = 200,
    public val responseContentType: String? = response?.let { JSON },
) {
    public companion object {
        public const val JSON: String = "application/json"
        public const val PNG: String = "image/png"
        public const val HTML: String = "text/html"
    }
}

/** Every route installed from `server/routes`, in the order the server registers them. */
public val phoneApiEndpoints: List<ApiEndpoint> =
    listOf(
        endpoint(
            ApiMethod.GET,
            "/v1/device",
            Scope.OBSERVE,
            "Device identity, display, helper status, and capabilities.",
            response = serializer<DeviceInfo>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/wake",
            Scope.CONTROL,
            "Turn the screen on.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/unlock",
            Scope.CONTROL,
            "Dismiss the keyguard.",
            serializer<UnlockRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/device/lock",
            Scope.CONTROL,
            "Lock the device.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.PUT,
            "/v1/device/pin",
            Scope.ADMIN,
            "Store a lock-screen PIN.",
            serializer<SetPinRequest>(),
            serializer<PinStatus>(),
        ),
        endpoint(
            ApiMethod.DELETE,
            "/v1/device/pin",
            Scope.ADMIN,
            "Forget the stored PIN.",
            status = 204,
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/ui/snapshot",
            Scope.OBSERVE,
            "Accessibility snapshot of the current UI.",
            response = serializer<UiSnapshot>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ui/find",
            Scope.OBSERVE,
            "Find nodes matching a selector.",
            serializer<FindRequest>(),
            serializer<FindResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ui/nodes/{ref}/action",
            Scope.CONTROL,
            "Perform an action on a snapshot node.",
            serializer<NodeActionRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/screenshot",
            Scope.OBSERVE,
            "PNG screenshot.",
            responseContentType = ApiEndpoint.PNG,
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/tap",
            Scope.CONTROL,
            "Tap a point or a node.",
            serializer<TapRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/swipe",
            Scope.CONTROL,
            "Swipe between points or inside a node.",
            serializer<SwipeRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/gesture",
            Scope.CONTROL,
            "Replay pointer paths.",
            serializer<GestureRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/input/stream",
            Scope.CONTROL,
            "Live touch. One pointer, one socket. JSON text frames down, move, up, and cancel.",
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/key",
            Scope.CONTROL,
            "Press a key.",
            serializer<KeyRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/input/text",
            Scope.CONTROL,
            "Type text.",
            serializer<TextRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ime/hide",
            Scope.CONTROL,
            "Hide the soft keyboard.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/ime/show",
            Scope.CONTROL,
            "Focus an editable node and show the soft keyboard.",
            serializer<ImeShowRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/apps",
            Scope.OBSERVE,
            "List installed apps.",
            response = ListSerializer(serializer<AppInfo>()),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/apps/{pkg}/launch",
            Scope.CONTROL,
            "Launch an app.",
            serializer<LaunchRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/apps/{pkg}/stop",
            Scope.CONTROL,
            "Force-stop an app. Requires the helper.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/apps/{pkg}/clear",
            Scope.CONTROL,
            "Clear an app's data. Requires the helper.",
            response = serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/intents",
            Scope.CONTROL,
            "Start an intent.",
            serializer<IntentRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/wait",
            Scope.OBSERVE,
            "Wait until conditions match.",
            serializer<WaitRequest>(),
            serializer<WaitResult>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/browser/targets",
            Scope.BROWSER,
            "List Chrome tabs and debuggable WebViews.",
            response = ListSerializer(serializer<BrowserTarget>()),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/tabs",
            Scope.BROWSER,
            "Open a Chrome tab.",
            serializer<OpenTabRequest>(),
            serializer<BrowserTarget>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/navigate",
            Scope.BROWSER,
            "Navigate a browser target.",
            serializer<NavigateRequest>(),
            serializer<BrowserTarget>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/browser/targets/{id}/snapshot",
            Scope.BROWSER,
            "Compact outline of a browser target.",
            response = serializer<BrowserSnapshot>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/tap",
            Scope.BROWSER,
            "Tap a ref or CSS selector in a browser target.",
            serializer<BrowserTapRequest>(),
            serializer<ActionResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/evaluate",
            Scope.BROWSER,
            "Evaluate JavaScript in an isolated world.",
            serializer<EvalRequest>(),
            serializer<EvalResult>(),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/browser/targets/{id}/console",
            Scope.BROWSER,
            "Collect console entries.",
            serializer<ConsoleRequest>(),
            serializer<ConsoleResult>(),
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/browser/targets/{id}/cdp",
            Scope.BROWSER,
            "Raw Chrome DevTools Protocol socket.",
        ),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/events",
            Scope.OBSERVE,
            "Stream device events as JSON text frames. Logcat is included only when types contains logcat.",
            response = serializer<Event>(),
        ),
        endpoint(
            ApiMethod.GET,
            "/viewer",
            Scope.STREAM,
            "WebCodecs viewer page.",
            responseContentType = ApiEndpoint.HTML,
        ),
        endpoint(ApiMethod.WEBSOCKET, "/v1/stream/video", Scope.STREAM, "H.264 video stream."),
        endpoint(
            ApiMethod.WEBSOCKET,
            "/v1/stream/audio",
            Scope.STREAM,
            "Opus or AAC audio stream.",
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/tokens",
            Scope.ADMIN,
            "List bearer tokens.",
            response = ListSerializer(serializer<TokenInfo>()),
        ),
        endpoint(
            ApiMethod.POST,
            "/v1/tokens",
            Scope.ADMIN,
            "Create a bearer token.",
            serializer<CreateTokenRequest>(),
            serializer<CreatedToken>(),
            status = 201,
        ),
        endpoint(
            ApiMethod.DELETE,
            "/v1/tokens/{id}",
            Scope.ADMIN,
            "Revoke a bearer token.",
            status = 204,
        ),
        endpoint(
            ApiMethod.GET,
            "/v1/openapi.json",
            Scope.OBSERVE,
            "OpenAPI 3.1 document for this API.",
            response = serializer<JsonObject>(),
        ),
    )

private fun endpoint(
    method: ApiMethod,
    path: String,
    scope: Scope,
    summary: String,
    request: KSerializer<*>? = null,
    response: KSerializer<*>? = null,
    status: Int = if (method == ApiMethod.DELETE && response == null) 204 else 200,
    responseContentType: String? = null,
): ApiEndpoint =
    ApiEndpoint(
        method = method,
        path = path,
        scope = scope,
        summary = summary,
        request = request,
        response = response,
        status = status,
        responseContentType = responseContentType ?: response?.let { ApiEndpoint.JSON },
    )
