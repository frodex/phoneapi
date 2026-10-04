package net.die.phoneapi.stream

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A join or a sync asks for one redraw and one sync frame. A timer asks for neither.
 * Measured 2026-10-04: this encoder emits nothing for requestSync until the next input buffer.
 */
internal data class StillStep(val redraws: Int, val syncs: Int)

internal fun stillStep(event: String): StillStep =
    when (event) {
        "join", "sync" -> StillStep(redraws = 1, syncs = 1)
        else -> StillStep(redraws = 0, syncs = 0)
    }

/** A display frame is new input. A demand redraws the last picture once. A timer never submits. */
internal fun shouldSubmit(reason: String, haveFrame: Boolean): Boolean =
    when (reason) {
        "display" -> true
        "demand" -> haveFrame
        else -> false
    }

internal const val HEARTBEAT_EVERY_MS = 1000L

/** A join with no key gets another sync plus redraw after this long, up to [KEY_RETRY_LIMIT] times. */
internal const val KEY_RETRY_MS = 300L

internal const val KEY_RETRY_LIMIT = 3

/** Helper answer and encoder drain must both be at least this fresh, or the heartbeat stops. */
internal const val PIPELINE_FRESH_MS = 1000L

/**
 * A key emitted before this viewer is in [subscribers] is not queued for them.
 * Join adds the viewer before [demandKey], so the join's own sync is visible; a redraw that
 * already submitted its buffer before that add is not.
 */
internal fun viewerReceivesEmittedKey(alreadySubscribed: Boolean): Boolean = alreadySubscribed

/**
 * This encoder answers requestSync on the next input buffer only. A buffer submitted before
 * setParameters is not a key, and on a still screen that in-flight redraw may be the only one.
 */
internal fun syncAppliesTo(bufferSubmittedBeforeSync: Boolean): Boolean = !bufferSubmittedBeforeSync

internal class JoinKeyWatch {
    var attempts = 0
        private set

    private var lastAttemptAt = Long.MIN_VALUE
    private var sawKey = false

    /** The join's first sync plus redraw. */
    fun onJoin(now: Long): Boolean {
        attempts = 1
        lastAttemptAt = now
        return true
    }

    fun onKey() {
        sawKey = true
    }

    /** True when another sync plus redraw is due. Stops after [KEY_RETRY_LIMIT] retries. */
    fun retryDue(now: Long): Boolean {
        if (sawKey || attempts >= 1 + KEY_RETRY_LIMIT) return false
        if (now - lastAttemptAt < KEY_RETRY_MS) return false
        attempts += 1
        lastAttemptAt = now
        return true
    }
}

internal fun pipelineUp(helperAnswered: Boolean, lastDrainAt: Long, now: Long): Boolean =
    helperAnswered && now - lastDrainAt <= PIPELINE_FRESH_MS

internal fun aliveJson(ageMs: Long): String = """{"t":"alive","lastFrameMs":$ageMs}"""

internal fun isSyncFrame(text: String): Boolean = """"t"\s*:\s*"sync"""".toRegex().containsMatchIn(text)

/** Emits {"t":"alive"} at least every [HEARTBEAT_EVERY_MS], including when no picture arrives. */
internal class VideoHeartbeat {
    private var openedAt = 0L
    private var lastSentAt = Long.MIN_VALUE

    fun open(now: Long) {
        openedAt = now
    }

    fun poll(now: Long, lastFrameAt: Long, helperAnswered: Boolean, lastDrainAt: Long): String? {
        if (!pipelineUp(helperAnswered, lastDrainAt, now)) return null
        if (lastSentAt != Long.MIN_VALUE && now - lastSentAt < HEARTBEAT_EVERY_MS) return null
        lastSentAt = now
        val origin = if (lastFrameAt > 0) lastFrameAt else openedAt
        return aliveJson(now - origin)
    }
}

/**
 * The helper mirrors the display onto a [SurfaceTexture]. A new display frame is drawn once.
 * [demand] draws the last picture once. Nothing is scheduled on a timer.
 */
internal class OnDemandMirror(
    private val encoderSurface: Surface,
    private val width: Int,
    private val height: Int,
) {
    private val thread = HandlerThread("phoneapi-mirror")
    private val stopped = AtomicBoolean(false)
    private val ready = java.util.concurrent.CompletableFuture<Surface>()

    private var handler: Handler? = null
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null
    private var mirrorSurface: Surface? = null
    private var program = 0
    private var texId = 0
    private var positionLoc = 0
    private var texCoordLoc = 0
    private var texMatrixLoc = 0
    private var samplerLoc = 0
    private val texMatrix = FloatArray(16)
    private var vertices: ByteBuffer? = null
    private var pending = false
    private var haveFrame = false
    private var swapFailed = false

    /** One redraw of the last mirrored picture. No-op until the display has delivered a frame. */
    fun demand() {
        handler?.post {
            if (stopped.get()) return@post
            if (!shouldSubmit("demand", haveFrame)) return@post
            draw()
        }
    }

    fun start(): Surface {
        thread.start()
        val loop = thread.looper
        handler = Handler(loop)
        handler?.post {
            try {
                ready.complete(startGl())
            } catch (e: RuntimeException) {
                releaseGl()
                ready.completeExceptionally(e)
            }
        }
        return try {
            ready.get(START_WAIT_SEC, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            stop()
            throw IllegalStateException(e.cause?.message ?: "repeat surface", e.cause)
        } catch (e: TimeoutException) {
            stop()
            throw IllegalStateException("repeat surface timed out", e)
        }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        if (thread.state == Thread.State.NEW) return
        handler?.post { releaseGl() }
        thread.quitSafely()
        thread.join(STOP_WAIT_MS)
    }

    private fun startGl(): Surface {
        initEgl()
        initProgram()
        val mirror = initTexture()
        Log.i(TAG, "mirror ${width}x$height")
        return mirror
    }

    private fun initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) fail("eglGetDisplay")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) fail("eglInitialize")
        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)) {
            fail("eglChooseConfig")
        }
        val config = configs[0] ?: fail("eglChooseConfig")
        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (context == EGL14.EGL_NO_CONTEXT) fail("eglCreateContext")
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        window = EGL14.eglCreateWindowSurface(display, config, encoderSurface, surfaceAttribs, 0)
        if (window == EGL14.EGL_NO_SURFACE) fail("eglCreateWindowSurface")
        if (!EGL14.eglMakeCurrent(display, window, window, context)) fail("eglMakeCurrent")
        EGL14.eglSwapInterval(display, 0)
    }

    private fun initProgram() {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        val created = GLES20.glCreateProgram()
        if (created == 0) fail("glCreateProgram")
        GLES20.glAttachShader(created, vertex)
        GLES20.glAttachShader(created, fragment)
        GLES20.glLinkProgram(created)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(created)
            GLES20.glDeleteProgram(created)
            throw IllegalStateException("repeat program: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        program = created
        positionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        samplerLoc = GLES20.glGetUniformLocation(program, "sTexture")
        vertices = quad()
    }

    private fun initTexture(): Surface {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        val texture =
            SurfaceTexture(texId).apply {
                setDefaultBufferSize(width, height)
                setOnFrameAvailableListener(
                    {
                        pending = true
                        handler?.post {
                            if (!stopped.get()) renderDisplay()
                        }
                    },
                    handler,
                )
            }
        surfaceTexture = texture
        val mirror = Surface(texture)
        mirrorSurface = mirror
        return mirror
    }

    private fun renderDisplay() {
        val texture = surfaceTexture ?: return
        if (!pending) return
        pending = false
        try {
            texture.updateTexImage()
            texture.getTransformMatrix(texMatrix)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "mirror surface", e)
            return
        }
        haveFrame = true
        if (!shouldSubmit("display", haveFrame)) return
        draw()
    }

    private fun draw() {
        val buffer = vertices ?: return
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(samplerLoc, 0)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, texMatrix, 0)
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionLoc, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(positionLoc)
        buffer.position(8)
        GLES20.glVertexAttribPointer(texCoordLoc, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(texCoordLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
        if (!EGL14.eglSwapBuffers(display, window) && !swapFailed) {
            swapFailed = true
            Log.w(TAG, "eglSwapBuffers ${EGL14.eglGetError()}")
        }
    }

    private fun releaseGl() {
        mirrorSurface?.release()
        mirrorSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        if (texId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
            texId = 0
        }
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        window = EGL14.EGL_NO_SURFACE
    }

    private fun fail(what: String): Nothing =
        throw IllegalStateException("$what ${EGL14.eglGetError()}")

    private companion object {
        const val TAG = "PhoneApiStream"
        const val START_WAIT_SEC = 5L
        const val STOP_WAIT_MS = 2_000L
        const val STRIDE = 4 * 4
        const val EGL_RECORDABLE_ANDROID = 0x3142

        val VERTEX =
            """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
            """
                .trimIndent()

        val FRAGMENT =
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
            """
                .trimIndent()

        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            if (shader == 0) throw IllegalStateException("glCreateShader ${GLES20.glGetError()}")
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("repeat shader: $log")
            }
            return shader
        }

        fun quad(): ByteBuffer {
            val data =
                floatArrayOf(
                    -1f,
                    -1f,
                    0f,
                    0f,
                    1f,
                    -1f,
                    1f,
                    0f,
                    -1f,
                    1f,
                    0f,
                    1f,
                    1f,
                    1f,
                    1f,
                    1f,
                )
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(data)
            buffer.position(0)
            return buffer
        }
    }
}
