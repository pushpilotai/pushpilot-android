package ai.pushpilot.sample

import ai.pushpilot.sdk.PushPilot
import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class SampleApp : Application() {
    /** Starts PushPilot once per process and forwards the current FCM token. */
    override fun onCreate() {
        super.onCreate()
        PushPilot.init(this, BuildConfig.PUSHPILOT_SDK_KEY, BuildConfig.PUSHPILOT_BASE_URL.ifEmpty { null })
        forwardToken()
    }

    /** Hands the app's current FCM token to PushPilot; skipped when Firebase is not configured. */
    private fun forwardToken() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.w("PushPilotSample", "No google-services.json, so Firebase is not configured and there is no push token")
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { PushPilot.setPushToken(it) }
    }
}
