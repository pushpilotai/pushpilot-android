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
import java.util.UUID

class MainActivity : Activity() {
    /** Builds a plain screen with one button per SDK call and asks for notification permission. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val userId = EditText(this).apply { hint = "External user id" }
        val topics = EditText(this).apply { hint = "Topics, comma separated" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setPadding(48, 48, 48, 48)
        }
        layout.addView(userId)
        layout.addView(button("Set user id") { PushPilot.setExternalUserId(userId.text.toString()) })
        layout.addView(topics)
        layout.addView(button("Set topics") {
            PushPilot.setTopics(topics.text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        })
        layout.addView(button("Log out") { PushPilot.logout() })
        layout.addView(button("Forget this device") { PushPilot.forget() })
        layout.addView(button("Show a test push") { PushPilot.handleMessage(this, testPush()) })
        // What the app receives when a PushPilot notification is opened: the push's data as extras.
        layout.addView(TextView(this).apply { text = "Opened with order_id: ${intent.getStringExtra("order_id") ?: "-"}" })
        setContentView(layout)
        // The app asks for permission; the SDK only reports the result.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** A payload v2 message as the server sends it, for checking display and taps without a server. */
    private fun testPush() = mapOf(
        "source" to "pushpilot",
        // A fresh id each time, so every tap shows a new notification; its events reach the server as unknown ids.
        "notification_id" to UUID.randomUUID().toString(),
        "title" to "नमस्ते! आपका ऑर्डर भेज दिया गया है",
        "body" to "مرحبا · உங்கள் ஆர்டர் வந்துகொண்டிருக்கிறது 🚚",
        "image_url" to "https://picsum.photos/id/1015/1024/512",
        "buttons" to """[{"id":"track","label":"ट्रैक करें","url":"https://pushpilot.ai"},{"id":"later","label":"बाद में"}]""",
        "data" to """{"order_id":"42"}""",
    )

    /** A button that runs [onClick]. */
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
