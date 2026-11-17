package ai.pushpilot.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * PushPilot SDK entry point. Every method returns immediately and never throws; network and
 * disk work happen on one background thread. Calls made before [init] are queued and run after it.
 */
object PushPilot {
    /** PushPilot's hosted server; pass another base URL to [init] for a self-hosted one. */
    const val DEFAULT_BASE_URL = "https://pushpilot.ai"

    private val scheduler = ExecutorScheduler(
        Executors.newSingleThreadScheduledExecutor { Thread(it, "PushPilot").apply { isDaemon = true } },
    )
    private val dispatcher = Dispatcher(scheduler)

    /** The app's handler for tapped links; null lets the SDK open them. Read on the main thread at each tap. */
    @Volatile
    internal var linkHandler: LinkHandler? = null
        private set

    /** Receives the link of a tapped PushPilot notification; see [setLinkHandler]. */
    fun interface LinkHandler {
        /** Called on the main thread with the tapped button's link, or the push's own link. */
        fun onLink(link: Uri)
    }

    /** Asks for a sync check whenever an activity resumes, which also happens after the permission dialog closes. */
    private val foregroundWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) = dispatcher.submit { foreground() }

        // The remaining callbacks are required by the interface and unused.
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    /**
     * Starts the SDK with the app's public SDK key. Safe to call before Firebase starts; later calls
     * are ignored. The key and base URL are saved on the device, so in a later process where the app
     * has not called init yet (a push or a tap that starts a killed app), the SDK starts from them.
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, sdkKey: String, baseUrl: String? = null) {
        try {
            if (sdkKey.isBlank()) return log("init ignored: the SDK key is empty")
            val app = context.applicationContext ?: context
            val store = store(app)
            dispatcher.init(store, sdkKey.trim(), baseUrl ?: DEFAULT_BASE_URL) { engine(app, store) }
        } catch (e: Exception) {
            log("init failed: ${e.javaClass.simpleName}")
        }
    }

    /** For the SDK's own entry points: without init in this process, starts from the values the last init saved. */
    internal fun startFromSaved(context: Context) {
        val app = context.applicationContext ?: context
        val store = store(app)
        dispatcher.startFromSaved(store) { engine(app, store) }
    }

    private var fileStore: FileStore? = null

    /** The one state file of this process, shared by init and the entry points. */
    @Synchronized
    private fun store(app: Context): FileStore = fileStore ?: FileStore { app.noBackupFilesDir }.also { fileStore = it }

    /** Creates the engine (once per process) and starts watching for foregrounds. */
    private fun engine(app: Context, store: Store): Engine {
        if (app is Application) app.registerActivityLifecycleCallbacks(foregroundWatcher)
        return Engine(store, HttpTransport(store, DEFAULT_BASE_URL), scheduler) { deviceFields(app) }
    }

    /**
     * Forwards the app's FCM push address; null clears it. On firebase-messaging 25.1.0+ with
     * Firebase Installation IDs enabled, pass the FID from `onRegistered`; on older versions,
     * the registration token from `onNewToken` and at launch. Pass only one of the two.
     */
    @JvmStatic
    fun setPushToken(token: String?) = dispatcher.submit { setPushToken(token) }

    /**
     * What the SDK has reported for this installation, for a status or debug screen. The live flags
     * are those of the last sync the server accepted (null before one). It holds no token or key.
     */
    data class Status(
        val installationId: String?,
        val liveSupported: Boolean?,
        val liveEnabled: Boolean?,
        /** The user lets the app post promoted notifications (Android 16 and later). */
        val livePromoted: Boolean?,
        val lastSyncAtMs: Long?,
        val lastSyncResult: String?,
    )

    /** Calls [callback] on the main thread with the SDK's [Status]; before init it waits for the SDK to start. */
    @JvmStatic
    fun status(callback: (Status) -> Unit) = dispatcher.submit {
        val status = status()
        Handler(Looper.getMainLooper()).post { callback(status) }
    }

    /** Links this installation to the app's own user id after login; null clears it. */
    @JvmStatic
    fun setExternalUserId(id: String?) = dispatcher.submit { setExternalUserId(id) }

    /** Merges attributes declared in the dashboard; values are String, Number, Boolean, or null to remove. */
    @JvmStatic
    fun setAttributes(attributes: Map<String, Any?>) {
        val copy = HashMap(attributes)
        dispatcher.submit { setAttributes(copy) }
    }

    /** Replaces this installation's topic list. */
    @JvmStatic
    fun setTopics(topics: List<String>) {
        val copy = topics.toList()
        dispatcher.submit { setTopics(copy) }
    }

    /** Clears the external user id; the device stays registered and keeps receiving pushes. */
    @JvmStatic
    fun logout() = dispatcher.submit { logout() }

    /** Deletes this installation from PushPilot and clears local state; the next launch registers a new one. */
    @JvmStatic
    fun forget() = dispatcher.submit { forget() }

    /**
     * Call from `onMessageReceived` with `RemoteMessage.data`. For a PushPilot push it reports
     * delivery, shows the notification (with image and buttons) and returns true; a live activity
     * step draws, updates or ends that activity's notification instead; for any
     * other push it does nothing and returns false. Off the main thread it waits for the image
     * download, as FCM expects; on the main thread it shows the notification in the background.
     */
    @JvmStatic
    fun handleMessage(context: Context, data: Map<String, String>): Boolean {
        if (!Push.isOurs(data)) return false
        try {
            startFromSaved(context)
            if (LiveUpdate.isLive(data)) return true.also { showLive(context, data) }
            // Ours but unreadable: swallowed, so the app never shows it as its own push. parse logs why.
            val push = Push.parse(data) ?: return true
            val app = context.applicationContext ?: context
            dispatcher.submit { track(push.notificationId, "delivered") }
            val show = {
                try {
                    Notifier.show(app, push)
                } catch (e: Exception) {
                    log("push not shown: ${e.javaClass.simpleName}")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) Thread { show() }.start() else show()
        } catch (e: Exception) {
            log("handleMessage failed: ${e.javaClass.simpleName}")
        }
        return true
    }

    /** Draws a live activity step like [handleMessage] draws a push: in the background on the main thread. */
    private fun showLive(context: Context, data: Map<String, String>) {
        val update = LiveUpdate.parse(data) ?: return
        val app = context.applicationContext ?: context
        val show = {
            try {
                LiveNotifier.show(app, update)
            } catch (e: Exception) {
                log("live activity not shown: ${e.javaClass.simpleName}")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) Thread { show() }.start() else show()
    }

    /**
     * Sets who opens the links of tapped PushPilot notifications; null goes back to the default.
     *
     * - The handler receives the tapped button's `url`, or the push's `url` for a tap on the
     *   notification itself, on the main thread, once per tap. The SDK opens the app's launcher
     *   activity first (with the tap's extras, see [tappedNotification]), so the handler can route
     *   inside the app. It is not called for a button without a `url` or a push without one.
     * - It returns nothing: once set, it owns every link and the SDK never opens one as well.
     * - With no handler set, the SDK opens the link with an `ACTION_VIEW` intent, or the app when
     *   nothing on the device opens it.
     * - The handler set at the moment the tap is handled decides; links are not held for a handler set
     *   later. Android creates the `Application`, running its content providers and `onCreate`, before
     *   the tap is handled, also when the tap launches a killed app, so a handler set there gets that
     *   tap too; if none is set by then, the SDK opens that link. Either way it is opened once.
     *
     * The click is recorded whether or not a handler is set.
     */
    @JvmStatic
    fun setLinkHandler(handler: LinkHandler?) {
        linkHandler = handler
    }

    /**
     * The PushPilot notification whose tap started [intent], with its id and custom data; null for
     * any other intent. Read it from the intent your launcher activity gets in `onCreate`, or in
     * `onNewIntent` if it is `singleTop`, and from the intent of the activity a push's own link opens.
     */
    @JvmStatic
    fun tappedNotification(intent: Intent?): TappedNotification? = try {
        intent?.let { TappedNotification.from(it.getStringExtra(Notifier.EXTRA_NOTIFICATION_ID), it.getStringExtra(Notifier.EXTRA_DATA)) }
    } catch (e: Exception) {
        // A malformed extras bundle from another app must not crash the caller.
        null
    }

    /** Queues a tracking event from the SDK's own tap handlers. */
    internal fun track(notificationId: String, type: String, buttonId: String?) =
        dispatcher.submit { track(notificationId, type, buttonId) }
}

/** A tapped PushPilot notification, from [PushPilot.tappedNotification]. */
class TappedNotification internal constructor(
    /** PushPilot's id for the notification, the same one its events carry. */
    val notificationId: String,
    /** The push's custom data; empty when it had none. */
    val data: Map<String, String>,
) {
    internal companion object {
        /** Builds it from the SDK's extras; null without a notification id, so the intent did not come from a tap. */
        fun from(notificationId: String?, data: String?): TappedNotification? {
            val id = notificationId?.takeIf { it.isNotBlank() } ?: return null
            val json = try { data?.let { JSONObject(it) } } catch (e: JSONException) { null }
            return TappedNotification(id, Push.customData(json))
        }
    }
}

/**
 * Holds calls made before the engine exists and runs every call on the SDK thread once it does.
 * The engine starts at most once per process: from [init], or from the saved values for a push or
 * a tap that arrives first. A later [init] still saves its values, which the next request uses.
 */
internal class Dispatcher(private val scheduler: Scheduler) {
    private var engine: Engine? = null
    private var initialized = false
    private val queued = ArrayList<Engine.() -> Unit>()

    /** Saves [sdkKey] and [baseUrl] for later processes and starts the engine; false when init already ran. */
    @Synchronized
    fun init(store: Store, sdkKey: String, baseUrl: String, create: () -> Engine): Boolean {
        if (initialized) {
            log("init already called; ignored")
            return false
        }
        initialized = true
        // Scheduled before the engine starts, so its first request already uses these values.
        scheduler.schedule(0) {
            if (store.get(Engine.SDK_KEY) != sdkKey || store.get(Engine.BASE_URL) != baseUrl) {
                store.put(Engine.SDK_KEY to sdkKey, Engine.BASE_URL to baseUrl)
            }
        }
        startOnce(create)
        return true
    }

    /** Starts the engine from the values an earlier init saved, unless it runs already or nothing was saved. */
    @Synchronized
    fun startFromSaved(store: Store, create: () -> Engine) {
        if (engine != null || initialized) return
        // The store is read on the SDK thread only.
        scheduler.schedule(0) {
            if (store.get(Engine.SDK_KEY) == null) return@schedule log("PushPilot.init has never run on this device; calls wait for it")
            startOnce(create)
        }
    }

    /** Creates the engine if there is none and replays queued calls after its start. */
    @Synchronized
    private fun startOnce(create: () -> Engine) {
        if (engine != null) return
        val engine = create()
        this.engine = engine
        val pending = queued.toList()
        queued.clear()
        scheduler.schedule(0) {
            engine.start()
            pending.forEach { engine.it() }
        }
    }

    /** Runs [call] on the SDK thread, or queues it until the engine exists. */
    @Synchronized
    fun submit(call: Engine.() -> Unit) {
        val engine = engine ?: return run {
            // Bounded so an app that never calls init cannot grow this forever.
            if (queued.size < 100) queued += call
            log("PushPilot.init has not been called yet; the call is queued")
        }
        scheduler.schedule(0) { engine.call() }
    }
}
