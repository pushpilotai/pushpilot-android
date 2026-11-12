package ai.pushpilot.sdk

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Looper
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

    /** Starts the SDK with the app's public SDK key. Safe to call more than once and before Firebase starts. */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, sdkKey: String, baseUrl: String? = null) {
        try {
            if (sdkKey.isBlank()) return log("init ignored: the SDK key is empty")
            val app = context.applicationContext ?: context
            val transport = HttpTransport(baseUrl ?: DEFAULT_BASE_URL, sdkKey.trim())
            val engine = Engine(FileStore { app.noBackupFilesDir }, transport, scheduler) { deviceFields(app) }
            if (dispatcher.start(engine) && app is Application) app.registerActivityLifecycleCallbacks(foregroundWatcher)
        } catch (e: Exception) {
            log("init failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Forwards the app's FCM push address; null clears it. On firebase-messaging 25.1.0+ with
     * Firebase Installation IDs enabled, pass the FID from `onRegistered`; on older versions,
     * the registration token from `onNewToken` and at launch. Pass only one of the two.
     */
    @JvmStatic
    fun setPushToken(token: String?) = dispatcher.submit { setPushToken(token) }

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
     * delivery, shows the notification (with image and buttons) and returns true; for any
     * other push it does nothing and returns false. Off the main thread it waits for the image
     * download, as FCM expects; on the main thread it shows the notification in the background.
     */
    @JvmStatic
    fun handleMessage(context: Context, data: Map<String, String>): Boolean {
        if (!Push.isOurs(data)) return false
        try {
            val push = Push.parse(data) ?: return true.also { log("PushPilot push without a notification id or title; not shown") }
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

    /** Queues a tracking event from the SDK's own tap handlers. */
    internal fun track(notificationId: String, type: String, buttonId: String?) =
        dispatcher.submit { track(notificationId, type, buttonId) }
}

/** Holds calls made before init and runs every call on the SDK thread once the engine exists. */
internal class Dispatcher(private val scheduler: Scheduler) {
    private var engine: Engine? = null
    private val queued = ArrayList<Engine.() -> Unit>()

    /** Installs the engine once and replays queued calls after its start; returns false if already started. */
    @Synchronized
    fun start(engine: Engine): Boolean {
        if (this.engine != null) {
            log("init already called; ignored")
            return false
        }
        this.engine = engine
        val pending = queued.toList()
        queued.clear()
        scheduler.schedule(0) {
            engine.start()
            pending.forEach { engine.it() }
        }
        return true
    }

    /** Runs [call] on the SDK thread, or queues it until init. */
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
