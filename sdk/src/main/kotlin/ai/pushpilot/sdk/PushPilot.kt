package ai.pushpilot.sdk

import android.content.Context
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

    /** Starts the SDK with the app's public SDK key. Safe to call more than once and before Firebase starts. */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, sdkKey: String, baseUrl: String? = null) {
        try {
            if (sdkKey.isBlank()) return log("init ignored: the SDK key is empty")
            val app = context.applicationContext ?: context
            val transport = HttpTransport(baseUrl ?: DEFAULT_BASE_URL, sdkKey.trim())
            dispatcher.start(Engine(FileStore { app.noBackupFilesDir }, transport, scheduler) { deviceFields(app) })
        } catch (e: Exception) {
            log("init failed: ${e.javaClass.simpleName}")
        }
    }

    /** Forwards the FCM token from `onNewToken` and at launch; null clears it. */
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

    /** True when a received push came from PushPilot. Pass `RemoteMessage.data`; makes no network call. */
    @JvmStatic
    fun handleMessage(data: Map<String, String>): Boolean = data["source"] == "pushpilot"
}

/** Holds calls made before init and runs every call on the SDK thread once the engine exists. */
internal class Dispatcher(private val scheduler: Scheduler) {
    private var engine: Engine? = null
    private val queued = ArrayList<Engine.() -> Unit>()

    /** Installs the engine once and replays queued calls after its start; later calls are ignored. */
    @Synchronized
    fun start(engine: Engine) {
        if (this.engine != null) return log("init already called; ignored")
        this.engine = engine
        val pending = queued.toList()
        queued.clear()
        scheduler.schedule(0) {
            engine.start()
            pending.forEach { engine.it() }
        }
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
