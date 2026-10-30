package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.model.schema.LenientEnumSerializer

@Serializable
public data class ApiError(
    val error: String,
    val message: String? = null,
    val state: DeviceStateSummary? = null,
)

@Serializable public data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

@Serializable public data class TreeKey(val label: String, val id: String? = null, val bounds: Rect)

@Serializable
public data class PinpadKeys(val digits: Map<String, Rect> = emptyMap(), val submit: Rect? = null)

@Serializable public data class Point(val x: Float, val y: Float)

@Serializable
public enum class ScreenState {
    @SerialName("on") ON,
    @SerialName("off") OFF,
    @SerialName("doze") DOZE,
}

@Serializable public data class KeyguardState(val locked: Boolean, val secure: Boolean)

@Serializable
public data class ImeState(
    val visible: Boolean,
    val bounds: Rect? = null,
    @SerialName("package") val packageName: String? = null,
)

@Serializable
public enum class HelperStatus {
    @SerialName("running") RUNNING,
    @SerialName("starting") STARTING,
    @SerialName("needs_pairing") NEEDS_PAIRING,
    @SerialName("needs_usb") NEEDS_USB,
    @SerialName("stopped") STOPPED,
}

@Serializable
public data class DeviceStateSummary(
    val screen: ScreenState,
    val keyguard: KeyguardState,
    val ime: ImeState,
    val foregroundPackage: String? = null,
)

@Serializable
public data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val rotation: Int,
    val refreshRate: Float,
    /** True when system auto-rotate is on. An app can still force its own orientation. */
    val autoRotate: Boolean = false,
)

@Serializable
public data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val release: String,
    val appVersion: String,
    val display: DisplayInfo,
    val state: DeviceStateSummary,
    val helper: HelperStatus,
    val helperRecoveredAtMs: Long? = null,
    /** The helper's UiAutomation session is up. */
    val uiAutomationConnected: Boolean,
    val capabilities: Capabilities,
    /**
     * Whether Android's cached-app freezer is on, as `use_freezer` in `dumpsys activity` said when
     * the helper last started. Null when that is not known, as before Android 11.
     */
    val appFreezer: Boolean? = null,
    /**
     * Whether PhoneAPI holds a binding to Chrome's Custom Tabs service, which keeps the freezer off
     * Chrome. It does while the helper runs.
     */
    val chromeKept: Boolean = false,
    /**
     * Who has the screen now: `you` (the calling session), `parent` (the session that started it
     * with `session_start`), `subagent` (a session it started), `agent`, `person` (in the viewer),
     * or `script` (a REST session). Absent when it is free.
     */
    val screenHolder: String? = null,
    /** How long [screenHolder] has had the screen. */
    val screenHeldMs: Long? = null,
    /** The device owner's configured policy for release cleanup. */
    val screenCleanupOnRelease: ScreenCleanupOnRelease = ScreenCleanupOnRelease.HOME_LOCK,
)

/** Runtime feature flags. A flag is true only when that path works on this device right now. */
@Serializable
public data class Capabilities(
    @SerialName("ui.snapshot") val uiSnapshot: Boolean,
    @SerialName("input.inject") val inputInject: Boolean,
    @SerialName("text.keyevent") val textKeyevent: Boolean,
    @SerialName("screenshot.helper") val screenshotHelper: Boolean,
    @SerialName("ui.stableIds") val uiStableIds: Boolean,
    @SerialName("apps.manage") val appsManage: Boolean,
    @SerialName("logcat.all") val logcatAll: Boolean,
    @SerialName("browser.cdp") val browserCdp: Boolean,
    @SerialName("stream.video.mirror") val streamVideoMirror: Boolean,
    @SerialName("stream.audio.submix") val streamAudioSubmix: Boolean,
    @SerialName("encoder.lowLatency") val encoderLowLatency: Boolean,
    @SerialName("adb.wireless") val adbWireless: Boolean,
    @SerialName("settings.secure") val settingsSecure: Boolean,
    @SerialName("clipboard") val clipboard: Boolean,
)

/**
 * `auto` follows the sensor. `lock` freezes the current rotation. `0`, `90`, `180`, and `270` lock
 * to that rotation from the device's natural orientation (portrait on a phone).
 */
@Serializable(with = ScreenOrientationSerializer::class)
public enum class ScreenOrientation {
    @SerialName("auto") AUTO,
    @SerialName("lock") LOCK,
    @SerialName("0") R0,
    @SerialName("90") R90,
    @SerialName("180") R180,
    @SerialName("270") R270,
}

public object ScreenOrientationSerializer :
    LenientEnumSerializer<ScreenOrientation>(
        serialName = "net.die.phoneapi.model.ScreenOrientation",
        wires = listOf("auto", "lock", "0", "90", "180", "270"),
        values = { enumValues<ScreenOrientation>() },
    )

@Serializable
public data class OrientationRequest(
    @Doc(
        "auto follows the sensor. lock freezes the current rotation. 0, 90, 180, and 270 lock to that rotation from the device's natural orientation."
    )
    val orientation: ScreenOrientation
)

/** Body of `PUT /v1/device/pin`. The value is stored and never returned. */
@Serializable public data class SetPinRequest(val pin: String)

/** Whether a lock-screen PIN is stored. The PIN itself is never included. */
@Serializable public data class PinStatus(val stored: Boolean)
