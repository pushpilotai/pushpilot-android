package ai.pushpilot.sample

import ai.pushpilot.sdk.PushPilot
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : Activity() {
    private companion object {
        /** Whether the sample's link handler is set; kept per process, like the handler. */
        var routeLinks = false
    }

    /** What the SDK has reported, refreshed on resume and with the Refresh button. */
    private lateinit var statusView: TextView

    /** Builds a plain screen with the SDK's status, one button per SDK call, and asks for notification permission. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userId = EditText(this).apply { hint = "External user id" }
        val topics = EditText(this).apply { hint = "Topics, comma separated" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(48, 48, 48, 48)
        }
        statusView = TextView(this).apply { setTextIsSelectable(true) }
        layout.addView(statusView)
        layout.addView(button("Refresh status") { refreshStatus() })
        layout.addView(userId)
        layout.addView(button("Set user id") { PushPilot.setExternalUserId(userId.text.toString()) })
        layout.addView(topics)
        layout.addView(button("Set topics") {
            PushPilot.setTopics(topics.text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        })
        layout.addView(button("Log out") { PushPilot.logout() })
        layout.addView(button("Forget this device") { PushPilot.forget() })
        layout.addView(button("Show a test push") { PushPilot.handleMessage(this, testPush()) })
        val links = button(linkLabel()) {}
        links.setOnClickListener {
            // Lasts until the process dies; an app sets its handler in Application.onCreate to also get taps that launch it.
            routeLinks = !routeLinks
            PushPilot.setLinkHandler(if (routeLinks) PushPilot.LinkHandler { showLink(it.toString()) } else null)
            links.text = linkLabel()
        }
        layout.addView(links)
        // What the app receives when a PushPilot notification is opened: its id and data.
        val tapped = PushPilot.tappedNotification(intent)
        layout.addView(TextView(this).apply { text = "Opened from notification: ${tapped?.notificationId ?: "-"}, order_id: ${tapped?.data?.get("order_id") ?: "-"}" })
        setContentView(layout)
        // The app asks for permission; the SDK only reports the result.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** Re-reads the status whenever the screen comes back, as permission and syncs change it. */
    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Shows the server, installation id, live activity flags and the last sync. */
    private fun refreshStatus() {
        PushPilot.status { s ->
            /** "yes", "no", or "not reported" before the server accepted a sync. */
            fun yesNo(value: Boolean?) = value?.let { if (it) "yes" else "no" } ?: "not reported"
            statusView.text = listOf(
                "Server: ${BuildConfig.PUSHPILOT_BASE_URL.ifEmpty { PushPilot.DEFAULT_BASE_URL }}",
                "Installation id: ${s.installationId ?: "-"}",
                "Live activities supported: ${yesNo(s.liveSupported)}",
                "Live activities on: ${yesNo(s.liveEnabled)}",
                "Promoted look allowed: ${yesNo(s.livePromoted)}",
                "Last sync: ${s.lastSyncResult ?: "none yet"}" +
                    (s.lastSyncAtMs?.let { " (accepted ${DateFormat.getTimeInstance().format(Date(it))})" } ?: ""),
            ).joinToString("\n")
        }
    }

    /** A PushPilot message as the server sends it, for checking display and taps without a server. */
    private fun testPush(): Map<String, String> {
        val push = JSONObject()
            .put("source", "pushpilot")
            // A fresh id each time, so every tap shows a new notification; its events reach the server as unknown ids.
            .put("notification_id", UUID.randomUUID().toString())
            .put("title", "नमस्ते! आपका ऑर्डर भेज दिया गया है")
            .put("body", "مرحبا · உங்கள் ஆர்டர் வந்துகொண்டிருக்கிறது 🚚")
            .put("image_url", "https://picsum.photos/id/1015/1024/512")
            .put("buttons", JSONArray("""[{"id":"track","label":"ट्रैक करें","url":"https://pushpilot.ai"},{"id":"later","label":"बाद में"}]"""))
            .put("data", JSONObject().put("order_id", "42"))
        return mapOf("source" to "pushpilot", "pushpilot" to push.toString())
    }

    /** The link toggle's label for the current mode. */
    private fun linkLabel() = if (routeLinks) "Links: shown by the app (tap to let the SDK open them)" else "Links: opened by the SDK (tap to show them in the app)"

    /** What the sample's link handler does with a tapped link: shows it instead of opening it. */
    private fun showLink(link: String) = Toast.makeText(applicationContext, "Link from push: $link", Toast.LENGTH_LONG).show()

    /** A button that runs [onClick]. */
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
