package ai.pushpilot.sample

import ai.pushpilot.sdk.PushPilot
import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class SampleApp : Application() {
    /** Starts PushPilot once per process and asks Firebase for the current push address. */
    override fun onCreate() {
        super.onCreate()
        PushPilot.init(this, BuildConfig.PUSHPILOT_SDK_KEY, BuildConfig.PUSHPILOT_BASE_URL.ifEmpty { null })
        registerWithFirebase()
    }

    /** Triggers onRegistered, which forwards the FID to PushPilot; skipped when Firebase is not configured. */
    private fun registerWithFirebase() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            Log.w("PushPilotSample", "No google-services.json, so Firebase is not configured and there is no push address")
            return
        }
        FirebaseMessaging.getInstance().register()
    }
}
