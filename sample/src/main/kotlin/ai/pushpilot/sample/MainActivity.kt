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
        setContentView(layout)
        // The app asks for permission; the SDK only reports the result.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** A button that runs [onClick]. */
    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }
}
