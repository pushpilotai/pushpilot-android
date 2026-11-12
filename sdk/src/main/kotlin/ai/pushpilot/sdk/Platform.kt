package ai.pushpilot.sdk

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Debug-level log only; callers never pass the push token or SDK key. */
internal fun log(message: String) {
    Log.d("PushPilot", message)
}

/**
 * JSON file in the no-backup directory, which Android leaves out of Auto Backup and
 * device-to-device transfer, so a restored or cloned phone never reuses this installation id.
 */
internal class FileStore(dir: () -> File) : Store {
    // Resolved lazily so the first disk access happens on the SDK thread, not in init().
    private val file by lazy { File(dir(), "ai.pushpilot.sdk.json") }
    private val values by lazy { load() }

    /** Reads one value. */
    override fun get(key: String): String? = values[key]

    /** Applies all pairs, then replaces the file in one rename so a crash leaves the old or the new state. */
    override fun put(vararg pairs: Pair<String, String?>) {
        for ((key, value) in pairs) if (value == null) values.remove(key) else values[key] = value
        val temp = File(file.path + ".tmp")
        FileOutputStream(temp).use {
            it.write(JSONObject(values).toString().toByteArray())
            it.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("could not replace the state file")
    }

    /** Loads the file; a missing file is a fresh install and an unreadable one starts over. */
    private fun load(): HashMap<String, String> {
        val text = try {
            file.readText()
        } catch (e: FileNotFoundException) {
            return HashMap()
        }
        return try {
            val json = JSONObject(text)
            json.keys().asSequence().associateWithTo(HashMap()) { json.getString(it) }
        } catch (e: JSONException) {
            log("state file is corrupt; starting over")
            HashMap()
        }
    }
}

/** HttpURLConnection transport authenticated by the public SDK key that init saved in [store]. */
internal class HttpTransport(private val store: Store, private val defaultBaseUrl: String) : Transport {
    /** POSTs [json] with the saved key and base URL, read each time so a later init applies at once; I/O failures become -1, retried. */
    override fun post(path: String, json: String): Response = try {
        val sdkKey = store.get(Engine.SDK_KEY) ?: throw IOException("no saved SDK key")
        val baseUrl = store.get(Engine.BASE_URL) ?: defaultBaseUrl
        val connection = URL("${baseUrl.trimEnd('/')}/api/sdk/v1/$path/").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-SDK-Key", sdkKey)
            connection.outputStream.use { it.write(json.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code < 400) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(code, body, connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0)
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        log("$path request failed: ${e.javaClass.simpleName}")
        Response(-1)
    }
}

/** Runs tasks on the SDK's single thread and keeps any failure away from the host app. */
internal class ExecutorScheduler(private val executor: ScheduledExecutorService) : Scheduler {
    /** Wall-clock time, because the 24-hour rule spans process restarts. */
    override fun now() = System.currentTimeMillis()

    /** Schedules [task], catching anything it throws. */
    override fun schedule(delayMs: Long, task: () -> Unit) {
        executor.schedule({
            try {
                task()
            } catch (e: Exception) {
                log("internal error: ${e.javaClass.simpleName}")
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }
}

/** Fields the SDK reads itself on every sync; none prompts the user or identifies the hardware. */
internal fun deviceFields(context: Context): Map<String, String> = mapOf(
    "notification_permission" to notificationPermission(context),
    "os_version" to Build.VERSION.RELEASE.orEmpty().take(50),
    "app_version" to appVersion(context).take(50),
    "sdk_version" to BuildConfig.SDK_VERSION,
    "device_model" to "${Build.MANUFACTURER} ${Build.MODEL}".take(100),
    "locale" to Locale.getDefault().toLanguageTag().take(35),
    "timezone" to TimeZone.getDefault().id,
)

/** The permission state the app already obtained; never asks for it. */
internal fun notificationPermission(context: Context): String = when {
    Build.VERSION.SDK_INT >= 33 ->
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) "granted" else "denied"
    Build.VERSION.SDK_INT >= 24 ->
        if (context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "granted" else "denied"
    // Android 6 has no public API for this.
    else -> "unknown"
}

/** The host app's versionName, or empty if it has none. */
private fun appVersion(context: Context): String = try {
    val pm = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, 0)
    }
    info.versionName.orEmpty()
} catch (e: PackageManager.NameNotFoundException) {
    ""
}
