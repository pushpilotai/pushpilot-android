package ai.pushpilot.sdk.inapp

import ai.pushpilot.sdk.HttpTransport
import ai.pushpilot.sdk.PushPilot
import ai.pushpilot.sdk.Scheduler
import ai.pushpilot.sdk.Store
import ai.pushpilot.sdk.log
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLEncoder

/**
 * In-app messages (IN-APP-SDK.md): fetches on foreground, caches pages before they may show, and
 * shows them on `app_open` or [showInApp]. Network and disk run on the SDK thread ([scheduler]);
 * lifecycle, picking and showing on the main thread. [installationId] is read on the SDK thread;
 * [record] queues an event from any thread.
 */
internal class InApp(
    context: Context,
    private val scheduler: Scheduler,
    private val transport: HttpTransport,
    private val state: Store,
    private val installationId: () -> String?,
    private val record: (InAppEvent) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val slow = slowPhone(context)
    private val cache = InAppCache(File(context.cacheDir, "pushpilot-inapp"), slow, ::httpDownload) {
        Base64.encodeToString(it, Base64.NO_WRAP)
    }

    /** The last fetch answer; null until it was read from disk at start. */
    @Volatile
    private var fetched: Fetched? = null

    /** The SDK's own shown count per message id (section 3), read on main and saved on the SDK thread. */
    private val shown = HashMap<String, Int>()

    /** When the last fetch was tried (SDK thread), for the once-a-minute limit. */
    private var lastFetchAt: Long? = null

    // Main thread only from here.
    private var resumed: WeakReference<Activity>? = null
    private var started = 0
    private var changingConfiguration = false
    private var appOpenPending = false
    private var overlay: Overlay? = null
    private var showing: Showing? = null

    init {
        scheduler.schedule(0) { load() }
    }

    /** Reads the cached answer and counts from disk, then lets a waiting `app_open` show (SDK thread). */
    private fun load() {
        fetched = state.get(FETCH)?.let(::parseFetch) ?: Fetched.EMPTY
        state.get(SHOWN)?.let { text ->
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            synchronized(shown) { json.keys().forEach { shown[it] = json.optInt(it) } }
        }
        main.post { tryAppOpen() }
    }

    /** Fetches the eligible messages unless one was tried in the last minute, then caches them (SDK thread). */
    private fun fetch() {
        val now = scheduler.now()
        lastFetchAt?.let { if (now - it in 0 until FETCH_INTERVAL_MS) return }
        val id = installationId() ?: return
        lastFetchAt = now
        val response = transport.get("in-app", "installation_id=" + URLEncoder.encode(id, "UTF-8"))
        if (response.code !in 200..299) return log("in-app fetch not done (HTTP ${response.code}); tried again on a later foreground")
        val answer = parseFetch(response.body) ?: return log("in-app fetch answer unreadable")
        cache.update(answer.messages)
        state.put(FETCH to response.body, SHOWN to synchronized(shown) {
            // Counts of messages no longer listed are dropped; the server's count covers a message that comes back.
            shown.keys.retainAll(answer.messages.map { it.id }.toSet())
            JSONObject(shown as Map<*, *>).toString()
        })
        fetched = answer
    }

    /** Clears the cache, counts and answer for a forgotten installation (SDK thread). */
    fun forget() {
        cache.clear()
        synchronized(shown) { shown.clear() }
        state.put(FETCH to null, SHOWN to null)
        fetched = Fetched.EMPTY
        lastFetchAt = null
    }

    /** An activity started: the first one after none were started is a foreground, unless it is a rotation. */
    fun onStarted() {
        if (changingConfiguration) {
            changingConfiguration = false
            return
        }
        if (started++ == 0) {
            appOpenPending = true
            scheduler.schedule(0) { fetch() }
        }
    }

    /** An activity stopped; a rotation's stop is not counted, so its restart is not a new foreground. */
    fun onStopped(activity: Activity) {
        if (activity.isChangingConfigurations) changingConfiguration = true else started = maxOf(0, started - 1)
    }

    /** Remembers the activity to show over, and tries `app_open` once its window has laid out. */
    fun onResumed(activity: Activity) {
        resumed = WeakReference(activity)
        activity.window.decorView.post { tryAppOpen() }
    }

    /** Forgets [activity] as the one to show over. */
    fun onPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    /** A message over a destroyed activity is gone with it, so the showing ends. */
    fun onDestroyed(activity: Activity) {
        if (overlay?.activity === activity) close()
    }

    /** Shows the first eligible `app_open` message once per foreground, as soon as the cache is read and an activity is resumed. */
    private fun tryAppOpen() = contained {
        if (!appOpenPending || fetched == null) return@contained
        val activity = resumed?.get() ?: return@contained
        appOpenPending = false
        show(activity, APP_OPEN, null)
    }

    /** `PushPilot.showInApp(name)`: main thread only, so the answer is whether it is on screen. */
    fun showInApp(name: String): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false.also { log("showInApp must be called on the main thread; nothing shown") }
        val activity = resumed?.get() ?: return false
        return show(activity, SHOW_IN_APP, name)
    }

    /** Picks and shows a message over [activity]; false when another is on screen or none is eligible. */
    private fun show(activity: Activity, trigger: String, name: String?): Boolean {
        if (overlay != null || activity.isFinishing) return false
        val answer = fetched ?: return false
        val now = System.currentTimeMillis()
        val choice = pick(
            answer.messages, trigger, name, now,
            localShown = { synchronized(shown) { shown[it] ?: 0 } },
            target = { Marks.frame(it, activity) },
            page = { cache.read(it) },
        ) ?: return false
        val light = usesLight(choice.message, slow)
        val view = Overlay.show(activity, { insets, posture ->
            preparePage(choice.html, answer.origins, bridgeScript(insets, choice.target, light, posture))
        }, ::onMessage) ?: return false
        overlay = view
        val showing = Showing(choice.message.id, now).also { showing = it }
        val counts = synchronized(shown) {
            shown[choice.message.id] = (shown[choice.message.id] ?: 0) + 1
            JSONObject(shown as Map<*, *>).toString()
        }
        scheduler.schedule(0) { state.put(SHOWN to counts) }
        record(showing.shown())
        return true
    }

    /** Handles one bridge message from the page on screen; nothing it does may crash the app. */
    private fun onMessage(type: String, value: String) = contained {
        val view = overlay ?: return@contained
        when (type) {
            "close" -> close()
            "open" -> if (isOpenable(value)) {
                close()
                open(view.activity, value.trim())
            } else {
                log("pushpilot.open refused a link of a blocked kind")
            }
            "haptic" -> view.haptic(value)
            "track" -> showing?.track(value, System.currentTimeMillis())?.let(record)
                ?: log("pushpilot.track ignored: not a 1 to 64 byte id, or too many in one showing")
        }
    }

    /** Takes the message off screen and ends its showing (`dismissed` when nothing was tracked). */
    private fun close() {
        overlay?.remove()
        overlay = null
        showing?.close(System.currentTimeMillis())?.let(record)
        showing = null
    }

    /** Hands [url] to the app's link handler, or opens it with ACTION_VIEW like a push's link. */
    private fun open(activity: Activity, url: String) {
        val uri = Uri.parse(url)
        val handler = PushPilot.linkHandler
        if (handler != null) return handler.onLink(uri)
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            log("nothing opens the in-app link")
        }
    }

    /** Runs [block] on the main thread's posted work, logging instead of throwing into the app. */
    private inline fun contained(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            log("in-app error: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val FETCH = "fetch"
        const val SHOWN = "shown"
        const val FETCH_INTERVAL_MS = 60_000L

        /** Section 2's slow-phone rule from this device's memory. */
        fun slowPhone(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val memory = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
            return isSlowPhone(manager.isLowRamDevice, memory.totalMem)
        }
    }
}
