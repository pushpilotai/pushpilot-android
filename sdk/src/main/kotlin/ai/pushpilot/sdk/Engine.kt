package ai.pushpilot.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/** Durable key-value storage; `put` writes every pair at once, and a null value removes the key. */
internal interface Store {
    fun get(key: String): String?
    fun put(vararg pairs: Pair<String, String?>)
}

/** One HTTP answer; code -1 means no answer arrived (offline, timeout, DNS). */
internal class Response(val code: Int, val body: String = "", val retryAfterSeconds: Long = 0)

/** POSTs a JSON body to `/api/sdk/v1/{path}/`. */
internal fun interface Transport {
    fun post(path: String, json: String): Response
}

/** A clock plus a single background thread that runs tasks one at a time, in order. */
internal interface Scheduler {
    fun now(): Long
    fun schedule(delayMs: Long, task: () -> Unit)
}

/**
 * The sync engine. Every method runs on the [Scheduler] thread, so state needs no locks.
 * The whole desired state lives in [Store]; a sync is pending whenever the payload built
 * from it differs from the last one the server accepted, which survives process death.
 */
internal class Engine(
    private val store: Store,
    private val transport: Transport,
    private val scheduler: Scheduler,
    private val random: Random = Random.Default,
    private val device: () -> Map<String, String>,
) {
    private var scheduled = false
    private var blockedUntil = 0L
    private var failures = 0

    /** Creates the installation id on first launch, then syncs if anything changed or the last sync is a day old. */
    fun start() {
        if (store.get(ID) == null) store.put(ID to newId())
        val age = scheduler.now() - (store.get(SYNCED_AT)?.toLongOrNull() ?: 0L)
        // A negative age means the clock moved back; treat it as stale too.
        if (age !in 0 until DAY_MS) store.put(SENT to null)
        kick()
    }

    /** Stores the FCM token; null or blank clears it. */
    fun setPushToken(token: String?) = setString(TOKEN, token, 4096, "push token")

    /** Stores the app's own user id; null or blank clears it. */
    fun setExternalUserId(id: String?) = setString(EXTERNAL, id, 255, "external user id")

    /** Merges custom attributes; a null value removes that attribute on the server. */
    fun setAttributes(values: Map<String, Any?>) {
        val current = JSONObject(store.get(ATTRIBUTES) ?: "{}")
        for ((name, value) in values) {
            try {
                when (value) {
                    null -> current.put(name, JSONObject.NULL)
                    is String, is Boolean, is Number -> current.put(name, value)
                    else -> log("attribute $name must be a String, Number or Boolean; ignored")
                }
            } catch (e: Exception) {
                log("attribute $name is not a finite number; ignored")
            }
        }
        update(ATTRIBUTES to current.toString())
    }

    /** Replaces the topic list; names FCM would reject are dropped so they cannot fail the whole sync. */
    fun setTopics(topics: List<String>) {
        val valid = topics.filter { it.length <= 255 && TOPIC.matches(it) }
        if (valid.size < topics.size) log("${topics.size - valid.size} invalid topic names ignored")
        val unique = valid.distinct().sorted()
        if (unique.size > MAX_TOPICS) log("only the first $MAX_TOPICS topics are kept")
        update(TOPICS to JSONArray(unique.take(MAX_TOPICS)).toString())
    }

    /** Clears the external user id; the installation stays registered. */
    fun logout() = setExternalUserId(null)

    /** Deletes this installation on the server and resets the SDK to a fresh install. */
    fun forget() {
        // A never-synced id has no server row; keep any earlier forget that is still pending.
        val forgetId = if (store.get(SYNCED_AT) != null) store.get(ID) else store.get(FORGET)
        store.put(
            FORGET to forgetId, ID to newId(), TOKEN to null, EXTERNAL to null,
            TOPICS to null, ATTRIBUTES to null, SYNCED_AT to null,
        )
        // Nothing is registered again until the app sets something or the next launch.
        store.put(SENT to payload())
        kick(0)
    }

    /** Saves a validated string field and schedules a sync. */
    private fun setString(key: String, value: String?, max: Int, label: String) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        if (clean != null && clean.length > max) return log("$label is longer than $max characters; ignored")
        update(key to clean)
    }

    /** Writes state and schedules a sync. */
    private fun update(pair: Pair<String, String?>) {
        store.put(pair)
        kick()
    }

    /** Schedules one run unless one is already waiting, so rapid changes share one request. */
    private fun kick(delayMs: Long = COALESCE_MS) {
        if (scheduled) return
        scheduled = true
        scheduler.schedule(maxOf(delayMs, blockedUntil - scheduler.now())) {
            scheduled = false
            flush()
        }
    }

    /** Sends a pending forget first, then the sync if the payload differs from the last accepted one. */
    private fun flush() {
        store.get(FORGET)?.let { id ->
            post("forget", JSONObject().put("installation_id", id).toString()) ?: return
            store.put(FORGET to null)
        }
        val payload = payload()
        if (payload == store.get(SENT)) return
        val response = post(if (store.get(SYNCED_AT) == null) "register" else "sync", payload) ?: return
        if (response.code !in 200..299) return
        val json = try { JSONObject(response.body) } catch (e: Exception) { null }
        json?.optJSONArray("warnings")?.takeIf { it.length() > 0 }?.let { log("server warnings: $it") }
        val id = json?.optString("installation_id").orEmpty()
        if (id.isNotEmpty() && id != store.get(ID) && isUuid(id)) store.put(ID to id)
        store.put(SENT to payload(), SYNCED_AT to scheduler.now().toString())
    }

    /** POSTs and returns the response once settled (2xx, or a 4xx retrying cannot fix); null when a retry was scheduled. */
    private fun post(path: String, json: String): Response? {
        val response = transport.post(path, json)
        val code = response.code
        if (code == 429 || code >= 500 || code < 0) {
            failures++
            blockedUntil = scheduler.now() + maxOf(backoff(), response.retryAfterSeconds * 1000)
            kick(0)
            return null
        }
        failures = 0
        if (code !in 200..299) log("$path rejected with HTTP $code; not retried")
        return response
    }

    /** 1 s doubling to 5 min, each wait randomised to between half and all of it. */
    private fun backoff(): Long {
        val base = minOf(MAX_BACKOFF_MS, 1000L shl minOf(failures - 1, 20))
        return base / 2 + random.nextLong(base / 2 + 1)
    }

    /** Builds the full sync body; absent topics and attributes mean the app never set them. */
    private fun payload(): String {
        val json = JSONObject()
            .put("installation_id", store.get(ID))
            .put("token", store.get(TOKEN) ?: JSONObject.NULL)
            .put("external_user_id", store.get(EXTERNAL) ?: JSONObject.NULL)
        for ((name, value) in device()) json.put(name, value)
        store.get(TOPICS)?.let { json.put("topics", JSONArray(it)) }
        store.get(ATTRIBUTES)?.let { json.put("attributes", JSONObject(it)) }
        return json.toString()
    }

    companion object {
        const val COALESCE_MS = 2_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val MAX_BACKOFF_MS = 5 * 60 * 1000L
        const val MAX_TOPICS = 100
        const val ID = "installation_id"
        const val TOKEN = "token"
        const val EXTERNAL = "external_user_id"
        const val TOPICS = "topics"
        const val ATTRIBUTES = "attributes"
        const val SENT = "last_sent_payload"
        const val SYNCED_AT = "last_synced_at"
        const val FORGET = "pending_forget_id"

        /** The character set FCM accepts for topic names, matching the server's check. */
        private val TOPIC = Regex("[A-Za-z0-9._~%-]+")

        /** A new random installation id. */
        private fun newId() = UUID.randomUUID().toString()

        /** True when the server's id parses as a UUID. */
        private fun isUuid(id: String) = try { UUID.fromString(id); true } catch (e: IllegalArgumentException) { false }
    }
}
