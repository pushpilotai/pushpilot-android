package ai.pushpilot.sdk

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows live activities as one notification per activity, tagged with its id (LIVE.md "Design:
 * SDKs"). Promoted on Android 16 when the push asks for it and the user allows it; otherwise the
 * layout as a custom view. Reports `live_started` once, `live_dismissed` and `clicked`; never `delivered`.
 */
internal object LiveNotifier {
    const val CHANNEL_ID = "pushpilot_live"
    private const val EXTRA_ACTIVITY_ID = "ai.pushpilot.sdk.activity_id"
    private const val EXTRA_ENDS_AT = "ai.pushpilot.sdk.ends_at"
    private const val REQUEST_PROMOTED = "android.requestPromotedOngoing" // Notification.EXTRA_REQUEST_PROMOTED_ONGOING, public from 36.1
    private const val MAX_IMAGES = 4
    private const val IMAGE_SIDE = 256

    private var memory: LiveMemory? = null

    /** The one memory file of this process. */
    @Synchronized
    fun memory(context: Context): LiveMemory {
        val app = context.applicationContext ?: context
        return memory ?: LiveMemory(FileStore("ai.pushpilot.sdk.live.json") { app.noBackupFilesDir }).also { memory = it }
    }

    /** Draws, replaces or removes the activity's notification for [update]; blocks while its images download. */
    fun show(context: Context, update: LiveUpdate) {
        if (notificationPermission(context) == "denied") return log("notifications are not allowed; live activity not shown")
        val manager = context.getSystemService(NotificationManager::class.java)
        val now = System.currentTimeMillis()
        val memory = memory(context)
        // shortcut: Android 7 and older have no timeout; their finished activities go at the next live push, not on time.
        if (Build.VERSION.SDK_INT < 26) memory.expired(now / 1000).forEach { manager.cancel(it, 0) }
        when (memory.accept(update, now / 1000)) {
            LiveMemory.Verdict.DROP -> return
            LiveMemory.Verdict.SHOW_FIRST -> PushPilot.track(update.notificationId, "live_started", null)
            LiveMemory.Verdict.SHOW -> Unit
        }
        val until = if (update.event == "end") update.dismissAtSec ?: (now / 1000) else update.endsAtSec
        if (until * 1000 <= now) return manager.cancel(update.activityId, 0)
        val views = LiveViews(context, update, images(update), now)
        val builder = builder(context, manager)
            .setSmallIcon(Notifier.smallIcon(context, Notifier.metaData(context)))
            .setContentTitle(update.alertTitle ?: update.title)
            .setContentText(update.alertBody)
            .setOnlyAlertOnce(update.alertTitle == null)
            .setOngoing(update.event != "end")
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(tap(context, update))
            .setDeleteIntent(dismissed(context, update))
        views.color("accent")?.let { builder.setColor(it) }
        if (Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter(until * 1000 - now)
        if (update.promoted && canPromote(manager)) promoted(context, builder, update, views) else custom(builder, views)
        manager.notify(update.activityId, 0, builder.build())
    }

    /** A builder on the "Live updates" channel (default importance: it sounds once, then updates quietly). */
    private fun builder(context: Context, manager: NotificationManager): Notification.Builder {
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            return Notification.Builder(context).setPriority(Notification.PRIORITY_DEFAULT)
        }
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.pushpilot_live_channel), NotificationManager.IMPORTANCE_DEFAULT))
        return Notification.Builder(context, CHANNEL_ID)
    }

    /** True on Android 16 when the user lets the app post promoted notifications. */
    fun canPromote(manager: NotificationManager): Boolean = Build.VERSION.SDK_INT >= 36 && try {
        manager.canPostPromotedNotifications()
    } catch (e: NoSuchMethodError) {
        false // a 36.0 build without the 36.1 method
    }

    /** The layout's lock block as the notification's custom view. */
    private fun custom(builder: Notification.Builder, views: LiveViews) {
        val content = views.lock() ?: return
        if (Build.VERSION.SDK_INT >= 24) {
            builder.setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(content).setCustomBigContentView(content)
        } else {
            @Suppress("DEPRECATION")
            builder.setContent(content)
        }
    }

    /** The promoted look: the layout's `promoted` fields mapped onto the standard ones, never a custom view. */
    private fun promoted(context: Context, builder: Notification.Builder, update: LiveUpdate, views: LiveViews) {
        if (Build.VERSION.SDK_INT < 36) return
        val p = update.layout.optJSONObject("promoted") ?: JSONObject()
        builder.setContentTitle(update.fill(p.optString("title").ifEmpty { "{{title}}" }))
            .setContentText(update.fill(p.optString("text")).ifEmpty { update.alertBody })
            .setColorized(false)
        update.fill(p.optString("sub_text")).takeIf { it.isNotEmpty() }?.let { builder.setSubText(it) }
        update.fill(p.optString("chip")).take(7).takeIf { it.isNotEmpty() }?.let { builder.setShortCriticalText(it) }
        p.optJSONObject("progress")?.let { progressStyle(context, it, views, update)?.let(builder::setStyle) }
        p.optJSONObject("timer")?.let { timer ->
            update.time(timer.optString("field"))?.let {
                builder.setWhen(it * 1000).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(timer.optString("mode") != "up")
            }
        }
        p.optJSONObject("image")?.let { image ->
            val bitmap = views.bitmap(image)
            val asset = views.drawable(image.optString("asset"))
            when {
                bitmap != null -> builder.setLargeIcon(bitmap)
                asset != null -> builder.setLargeIcon(Icon.createWithResource(context, asset))
            }
        }
        builder.addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED, true) })
    }

    /** Android 16's progress: equal segments, points and a tracker icon, in the block's colour. */
    private fun progressStyle(context: Context, block: JSONObject, views: LiveViews, update: LiveUpdate): Notification.Style? {
        if (Build.VERSION.SDK_INT < 36) return null
        val style = Notification.ProgressStyle().setStyledByProgress(true)
        val color = views.color(block.optString("color").ifEmpty { "accent" })
        val segments = block.optInt("segments", 1).coerceIn(1, 10)
        repeat(segments) { i ->
            val length = 1000 / segments + if (i == segments - 1) 1000 % segments else 0
            style.addProgressSegment(Notification.ProgressStyle.Segment(length).apply { color?.let { setColor(it) } })
        }
        val points = block.optJSONArray("points") ?: JSONArray()
        for (i in 0 until points.length()) {
            val at = (points.optDouble(i).coerceIn(0.0, 1.0) * 1000).toInt()
            style.addProgressPoint(Notification.ProgressStyle.Point(at).apply { color?.let { setColor(it) } })
        }
        views.drawable("pushpilot_live_" + block.optString("tracker"))?.let { style.setProgressTrackerIcon(Icon.createWithResource(context, it)) }
        return style.setProgress((update.fraction(block, System.currentTimeMillis()) * 1000).toInt())
    }

    /** Downloads the layout's https images (after filling their tokens), at most four, small enough for a notification. */
    private fun images(update: LiveUpdate): Map<String, Bitmap> {
        val urls = LinkedHashSet<String>()
        collectUrls(update.layout, urls)
        return urls.map(update::fill).filter { it.startsWith("https://") }.distinct().take(MAX_IMAGES)
            .mapNotNull { url -> Notifier.download(url, IMAGE_SIDE)?.let { url to it } }.toMap()
    }

    /** Every `url` of an `image` block anywhere in the layout. */
    fun collectUrls(json: Any?, out: MutableSet<String>) {
        when (json) {
            is JSONObject -> {
                if (json.optString("type") == "image") json.optString("url").takeIf { it.isNotEmpty() }?.let(out::add)
                json.keys().forEach { collectUrls(json.opt(it), out) }
            }
            is JSONArray -> for (i in 0 until json.length()) collectUrls(json.opt(i), out)
        }
    }

    /** A tap: records `clicked` against the start and opens the activity's link, or the app. */
    private fun tap(context: Context, update: LiveUpdate): PendingIntent {
        val intent = Intent(context, TapActivity::class.java)
            .setData(Uri.Builder().scheme("pushpilot").authority(update.activityId).appendPath("live").build())
            .putExtra(Notifier.EXTRA_NOTIFICATION_ID, update.notificationId)
            .putExtra(Notifier.EXTRA_URL, update.url)
            .putExtra(Notifier.EXTRA_DATA, "{}")
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A swipe: [LiveReceiver] records `live_dismissed` once and the activity is never drawn again. */
    private fun dismissed(context: Context, update: LiveUpdate): PendingIntent {
        val intent = Intent(context, LiveReceiver::class.java)
            .setData(Uri.Builder().scheme("pushpilot").authority(update.activityId).appendPath("dismissed").build())
            .putExtra(EXTRA_ACTIVITY_ID, update.activityId)
            .putExtra(Notifier.EXTRA_NOTIFICATION_ID, update.notificationId)
            .putExtra(EXTRA_ENDS_AT, update.endsAtSec)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Handles a removed notification: a user's swipe before the end counts as dismissed; a timeout does not. */
    fun onRemoved(context: Context, intent: Intent) {
        val activity = intent.getStringExtra(EXTRA_ACTIVITY_ID) ?: return
        val start = intent.getStringExtra(Notifier.EXTRA_NOTIFICATION_ID) ?: return
        val now = System.currentTimeMillis() / 1000
        if (now >= intent.getLongExtra(EXTRA_ENDS_AT, 0)) return
        if (!memory(context).dismiss(activity, now)) return
        PushPilot.startFromSaved(context)
        PushPilot.track(start, "live_dismissed", null)
    }

    /** The `live` object of register and sync: supported everywhere, enabled while notifications and the channel are on. */
    fun capability(context: Context): JSONObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelOn = Build.VERSION.SDK_INT < 26 || manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        return JSONObject()
            .put("supported", true)
            .put("enabled", notificationPermission(context) != "denied" && channelOn)
            .put("promoted", canPromote(manager))
    }
}

/** Receives the delete intent of a live notification; the file write runs off the main thread. */
internal class LiveReceiver : BroadcastReceiver() {
    /** Hands the work to a short thread and keeps the broadcast alive until it is done. */
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                LiveNotifier.onRemoved(context, intent)
            } catch (e: Exception) {
                log("live dismissal not recorded: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
