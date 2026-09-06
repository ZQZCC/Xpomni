package ka.xpomni

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal const val FLUD_PLUS = "com.delphicoder.flud.paid"
private const val FLUD_ATTACH_HOOK_ID = "flud.attach"

internal fun XpOmniModule.hookFludTrackerUpdater() {
    val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
    attach.isAccessible = true

    intercept(attach, FLUD_ATTACH_HOOK_ID)
}

internal fun XpOmniModule.resolveFludHook(hookId: String?): Hooker? =
    if (hookId == FLUD_ATTACH_HOOK_ID) Hooker { chain -> handleFludAttach(chain) } else null

private fun XpOmniModule.handleFludAttach(chain: Chain): Any? =
    with(chain) {
        val result = proceed()
        val baseContext = getArg(0) as Context
        val context = baseContext.applicationContext ?: baseContext
        FludTrackerUpdater.updateIfNeeded(context) { message, error ->
            log(Log.ERROR, TAG, message, error)
        }
        result
    }

internal fun isFludTrackerUpdaterIdle(): Boolean = !FludTrackerUpdater.isUpdating

private object FludTrackerUpdater {
    private val SOURCES = arrayOf(
        "http://github.itzmx.com/1265578519/OpenTracker/master/tracker.txt",
        "https://tracker.adysec.com/trackers_best.txt",
    )

    private const val PREFS = "xpomni_flud_tracker"
    private const val KEY_LAST_DAY = "last_update_day"
    private const val KEY_LAST_VERSION = "last_update_version"
    private const val UPDATER_VERSION = 4
    private const val TRACKERS_FILE = "default_trackers.txt"
    private const val TOAST_START = "\u5f00\u59cb\u66f4\u65b0\u8ffd\u8e2a\u5668\u5217\u8868\uff0c\u8bf7\u7a0d\u7b49~"
    private const val TOAST_UPDATING = "\u8ffd\u8e2a\u5668\u6b63\u5728\u66f4\u65b0"
    private const val TOAST_NO_TRACKERS = "\u672a\u83b7\u53d6\u5230\u53ef\u7528\u8ffd\u8e2a\u5668"
    private const val TOAST_FAILED = "\u8ffd\u8e2a\u5668\u66f4\u65b0\u5931\u8d25\uff0c\u8bf7\u67e5\u770b Xposed \u65e5\u5fd7"
    private const val TOAST_PARTIAL_FAILED = "\u6709\u4e2a\u522b\u66f4\u65b0\u5730\u5740\u83b7\u53d6\u8ffd\u8e2a\u5668\u5217\u8868\u5931\u8d25\uff0c\u8bf7\u770b Xposed \u65e5\u5fd7"
    private const val TOAST_UPDATED_PREFIX = "\u8ffd\u8e2a\u5668\u5217\u8868\u66f4\u65b0\uff1a"
    private const val TOAST_UPDATED_SUFFIX = " \u6761"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackerPattern =
        Regex("""(?i)\b(?:udp|https?|wss?)://[^\s"'<>]+?announce[^\s"'<>]*""")

    private val updating = AtomicBoolean()

    val isUpdating: Boolean
        get() = updating.get()

    fun updateIfNeeded(
        context: Context,
        logError: (String, Throwable?) -> Unit,
    ) {
        val today = today()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_DAY, null) != today ||
            prefs.getInt(KEY_LAST_VERSION, 0) != UPDATER_VERSION
        ) {
            updateNow(context, logError)
        }
    }

    private fun updateNow(
        context: Context,
        logError: (String, Throwable?) -> Unit,
    ) {
        val appContext = context.applicationContext ?: context
        if (!updating.compareAndSet(false, true)) {
            toast(appContext, TOAST_UPDATING)
            return
        }

        toast(appContext, TOAST_START)
        Thread({
            try {
                val update = collectTrackers(logError)
                if (update.trackers.isEmpty()) {
                    toast(appContext, if (update.hasFailures) TOAST_FAILED else TOAST_NO_TRACKERS)
                } else {
                    writeDefaultTrackers(appContext, update.trackers)
                    appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_DAY, today())
                        .putInt(KEY_LAST_VERSION, UPDATER_VERSION)
                        .apply()

                    if (update.hasFailures) {
                        toast(appContext, TOAST_PARTIAL_FAILED)
                    }
                    toast(appContext, TOAST_UPDATED_PREFIX + update.trackers.size + TOAST_UPDATED_SUFFIX)
                }
            } catch (error: Throwable) {
                logError("failed to update Flud trackers", error)
                toast(appContext, TOAST_FAILED)
            } finally {
                updating.set(false)
            }
        }, "Xpomni-FludTrackers").start()
    }

    private fun collectTrackers(logError: (String, Throwable?) -> Unit): UpdateResult {
        val trackers = LinkedHashSet<String>()
        var hasFailures = false

        for (source in SOURCES) {
            runCatching {
                parseTrackers(fetch(source), trackers)
            }.onFailure { error ->
                hasFailures = true
                logError("failed to fetch trackers from $source", error)
            }
        }

        return UpdateResult(trackers, hasFailures)
    }

    private fun parseTrackers(
        text: String,
        output: MutableSet<String>,
    ) {
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val tracker = cleanTracker(line)
                if (trackerPattern.matches(tracker)) {
                    output += tracker
                } else {
                    trackerPattern.findAll(line).forEach { output += cleanTracker(it.value) }
                }
            }
    }

    private fun cleanTracker(value: String): String {
        return value.trim().trim('"', '\'', ',', ';')
    }

    private fun fetch(source: String): String {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.errorStream?.close()
                throw IOException("HTTP $responseCode")
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeDefaultTrackers(
        context: Context,
        trackers: Set<String>,
    ) {
        val file = AtomicFile(context.filesDir.resolve(TRACKERS_FILE))
        val stream = file.startWrite()
        try {
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            trackers.forEach { tracker ->
                writer.write(tracker)
                writer.newLine()
            }
            writer.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun today(): String {
        return SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
    }

    private fun toast(
        context: Context,
        text: String,
    ) {
        mainHandler.post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    private data class UpdateResult(
        val trackers: Set<String>,
        val hasFailures: Boolean,
    )
}
