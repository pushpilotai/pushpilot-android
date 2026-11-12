package ai.pushpilot.sdk

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Builds and posts the notification for a PushPilot push. Decisions live in [Push]; this only talks to Android. */
internal object Notifier {
    const val CHANNEL_ID = "pushpilot_default"
    const val EXTRA_NOTIFICATION_ID = "ai.pushpilot.sdk.notification_id"
    const val EXTRA_BUTTON_ID = "ai.pushpilot.sdk.button_id"
    const val EXTRA_URL = "ai.pushpilot.sdk.url"
    const val EXTRA_DATA = "ai.pushpilot.sdk.data"
    private const val META_CHANNEL_NAME = "ai.pushpilot.sdk.notification_channel_name"
    private const val META_ICON = "ai.pushpilot.sdk.notification_icon"
    private const val FIREBASE_ICON = "com.google.firebase.messaging.default_notification_icon"
    private const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
    private const val MAX_IMAGE_SIDE = 1440
    private const val IMAGE_TIMEOUT_MS = 5_000
    private const val IMAGE_DEADLINE_MS = 10_000L

    /** Shows [push], blocking while its image downloads; shows nothing when the app may not post notifications. */
    fun show(context: Context, push: Push) {
        if (notificationPermission(context) == "denied") return log("notifications are not allowed; push not shown")
        val manager = context.getSystemService(NotificationManager::class.java)
        val meta = metaData(context)
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            val name = meta?.getString(META_CHANNEL_NAME)?.takeIf { it.isNotBlank() } ?: "Notifications"
            // Idempotent; a changed name from the app's meta-data is applied on the next push.
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH))
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
        }
        builder.setSmallIcon(smallIcon(context, meta))
            .setContentTitle(push.title)
            .setContentText(push.body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent(context, push, null))
        val image = push.imageUrl?.let { download(it) }
        when {
            image != null -> builder.setStyle(Notification.BigPictureStyle().bigPicture(image).setSummaryText(push.body))
            push.body != null -> builder.setStyle(Notification.BigTextStyle().bigText(push.body))
        }
        for (button in push.buttons) {
            builder.addAction(Notification.Action.Builder(null as Icon?, button.label, pendingIntent(context, push, button.id)).build())
        }
        // The notification id as tag: a repeated delivery replaces itself, and taps cancel it by tag.
        manager.notify(push.notificationId, 0, builder.build())
    }

    /** Records a tap as `clicked` and removes the notification; starts the SDK from saved values if the app has not. */
    fun recordTap(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_NOTIFICATION_ID) ?: return
        PushPilot.startFromSaved(context)
        PushPilot.track(id, "clicked", intent.getStringExtra(EXTRA_BUTTON_ID))
        context.getSystemService(NotificationManager::class.java).cancel(id, 0)
    }

    /** A tap target with no trampoline: an activity for links and the app, a receiver for dismiss-only buttons. */
    private fun pendingIntent(context: Context, push: Push, buttonId: String?): PendingIntent {
        val tap = push.tap(buttonId)
        val dismiss = tap is Tap.Dismiss
        val intent = Intent(context, if (dismiss) ActionReceiver::class.java else TapActivity::class.java)
            // A unique data URI keeps each notification's and button's PendingIntent separate.
            .setData(Uri.Builder().scheme("pushpilot").authority(push.notificationId).appendPath(buttonId ?: "body").build())
            .putExtra(EXTRA_NOTIFICATION_ID, push.notificationId)
            .putExtra(EXTRA_BUTTON_ID, buttonId)
            .putExtra(EXTRA_URL, (tap as? Tap.OpenUrl)?.url)
            .putExtra(EXTRA_DATA, JSONObject(push.data).toString())
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (dismiss) {
            PendingIntent.getBroadcast(context, 0, intent, flags)
        } else {
            PendingIntent.getActivity(context, 0, intent, flags)
        }
    }

    /** The app's manifest meta-data, or null. */
    private fun metaData(context: Context): Bundle? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        info.metaData
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Our meta-data icon, else Firebase's default notification icon, else the app's own icon. */
    private fun smallIcon(context: Context, meta: Bundle?): Int =
        meta?.getInt(META_ICON)?.takeIf { it != 0 }
            ?: meta?.getInt(FIREBASE_ICON)?.takeIf { it != 0 }
            ?: context.applicationInfo.icon.takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info

    /** Downloads an https image within the size cap and deadline; null on any failure. */
    private fun download(url: String): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = IMAGE_TIMEOUT_MS
            connection.readTimeout = IMAGE_TIMEOUT_MS
            if (connection.responseCode != 200 || connection.contentLength > MAX_IMAGE_BYTES) {
                null
            } else {
                connection.inputStream.use { readCapped(it) }?.let { decode(it) }
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        log("push image not loaded: ${e.javaClass.simpleName}")
        null
    }

    /** Reads at most [MAX_IMAGE_BYTES] within [IMAGE_DEADLINE_MS]; null when either is exceeded. */
    private fun readCapped(input: InputStream): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + IMAGE_DEADLINE_MS
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > MAX_IMAGE_BYTES || SystemClock.elapsedRealtime() > deadline) return null
        }
    }

    /** Decodes, downsampled so the longer side is at most [MAX_IMAGE_SIDE] pixels. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Where a tap goes, once: without a link it opens the app. With a link and a [handler] set, the app
 * is opened and the handler gets the link; without one, the SDK opens the link, or the app when nothing
 * can open the link. The link is never both handed over and opened.
 */
internal fun routeTap(url: String?, handler: ((String) -> Unit)?, openApp: () -> Unit, openLink: (String) -> Boolean) {
    when {
        url == null -> openApp()
        handler != null -> {
            openApp()
            handler(url)
        }
        !openLink(url) -> openApp()
    }
}

/** No-UI activity behind body taps and link buttons: records the click, then routes the link (see [routeTap]). */
internal class TapActivity : Activity() {
    /** Does all its work here and finishes before it would ever draw. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated instance already handled its tap; handling it again would count and open it twice.
        if (savedInstanceState == null) {
            try {
                Notifier.recordTap(this, intent)
                open(intent.getStringExtra(Notifier.EXTRA_URL), tapExtras(intent))
            } catch (e: Exception) {
                log("tap failed: ${e.javaClass.simpleName}")
            }
        }
        finish()
    }

    /** Only the extras [PushPilot.tappedNotification] reads, so nothing of the push lands in the app's own extra names. */
    private fun tapExtras(intent: Intent) = Bundle().apply {
        putString(Notifier.EXTRA_NOTIFICATION_ID, intent.getStringExtra(Notifier.EXTRA_NOTIFICATION_ID))
        putString(Notifier.EXTRA_DATA, intent.getStringExtra(Notifier.EXTRA_DATA))
    }

    /** Routes [url] with the handler set right now; [extras] go on whichever activity is opened. */
    private fun open(url: String?, extras: Bundle) {
        val handler = PushPilot.linkHandler
        routeTap(
            url,
            handler?.let { { link: String -> it.onLink(Uri.parse(link)) } },
            openApp = {
                // CLEAR_TOP so an already-open launcher activity is recreated with the new extras instead of only brought forward.
                packageManager.getLaunchIntentForPackage(packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    ?.putExtras(extras)
                    ?.let { startActivity(it) }
            },
            openLink = { link ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtras(extras))
                    true
                } catch (e: ActivityNotFoundException) {
                    log("nothing opens the push link; opening the app")
                    false
                }
            },
        )
    }
}

/** Handles buttons without a link: records the click and dismisses the notification. */
internal class ActionReceiver : BroadcastReceiver() {
    /** Runs on the main thread; the event write itself happens on the SDK thread. */
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Notifier.recordTap(context, intent)
        } catch (e: Exception) {
            log("button tap failed: ${e.javaClass.simpleName}")
        }
    }
}
