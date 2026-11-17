package ai.pushpilot.sample

import ai.pushpilot.sdk.PushPilot
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64

/**
 * Debug builds only: draws a live activity step without a server or Firebase, so each layout
 * can be checked on an emulator. The step is the `pushpilot_live` JSON, base64 encoded:
 * `adb shell am broadcast -n ai.pushpilot.sample/.DebugLiveReceiver --es live <base64>`.
 */
class DebugLiveReceiver : BroadcastReceiver() {
    /** Hands the step to the SDK exactly as an FCM data message would arrive. */
    override fun onReceive(context: Context, intent: Intent) {
        val live = intent.getStringExtra("live")?.let { String(Base64.decode(it, Base64.DEFAULT)) } ?: return
        PushPilot.handleMessage(context, mapOf("source" to "pushpilot", "pushpilot_live" to live))
    }
}
