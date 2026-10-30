package net.die.phoneapi.core

import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import net.die.phoneapi.model.ScreenCleanupOnRelease

@Serializable
data class Settings(
    /** Preferred local TCP port for `adb forward` onto the abstract socket. */
    val port: Int,
    val keepAwakeMs: Long = 60_000,
    @Serializable(with = StoredScreenCleanupSerializer::class)
    val screenCleanupOnRelease: ScreenCleanupOnRelease = ScreenCleanupOnRelease.HOME_LOCK,
)

/**
 * Disk reads and writes for the JSON stores. Callers stay synchronous; work that would otherwise
 * run on the main thread is moved to a single background thread.
 */
internal object StoreIo {
    @Volatile private var main: Thread? = null

    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phoneapi-store").apply { isDaemon = true }
        }
    }

    fun markMain(thread: Thread) {
        main = thread
    }

    fun <T> call(block: () -> T): T {
        if (Thread.currentThread() !== main) return block()
        return CompletableFuture.supplyAsync(block, executor).join()
    }
}

/** Small JSON-file settings store in the app's private files directory. */
class SettingsStore(private val dir: File) {
    private val atomic = AtomicFile(File(dir, SETTINGS_FILE_NAME))
    private val state: MutableStateFlow<Settings> by lazy { MutableStateFlow(load()) }

    val settings: StateFlow<Settings> by lazy { state.asStateFlow() }

    val current: Settings
        get() = state.value

    @Synchronized
    fun update(transform: (Settings) -> Settings) {
        val next = transform(current)
        StoreIo.call { atomic.writeUtf8(ApiJson.encodeToString(serializer<Settings>(), next)) }
        state.value = next
    }

    private fun load(): Settings = StoreIo.call {
        atomic.readUtf8()?.let { text ->
            runCatching { ApiJson.decodeFromString(serializer<Settings>(), text) }.getOrNull()
        }
            ?: run {
                val random = SecureRandom()
                val created =
                    Settings(port = EPHEMERAL_MIN + random.nextInt(EPHEMERAL_MAX - EPHEMERAL_MIN))
                dir.mkdirs()
                atomic.writeUtf8(ApiJson.encodeToString(serializer<Settings>(), created))
                created
            }
    }

    private companion object {
        const val SETTINGS_FILE_NAME = "settings.json"
        // Stay clear of well-known and commonly scanned ports.
        const val EPHEMERAL_MIN = 20_000
        const val EPHEMERAL_MAX = 60_000
    }
}

fun randomToken(random: SecureRandom, bytes: Int): String {
    val buf = ByteArray(bytes)
    random.nextBytes(buf)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
}

internal fun AtomicFile.readUtf8(): String? {
    if (!baseFile.exists()) return null
    return readFully().decodeToString()
}

internal fun AtomicFile.writeUtf8(text: String) = writeAll(text.toByteArray(Charsets.UTF_8))

@Suppress("MissingUseCall") // finishWrite and failWrite close the stream.
internal fun AtomicFile.writeAll(bytes: ByteArray) {
    val out = startWrite()
    try {
        out.write(bytes)
    } catch (e: IOException) {
        failWrite(out)
        throw e
    }
    finishWrite(out)
}
