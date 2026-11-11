package ai.pushpilot.sample

import ai.pushpilot.sdk.PushPilot
import android.annotation.SuppressLint
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** The app's own FCM service; PushPilot never registers one. */
@SuppressLint("MissingFirebaseInstanceTokenRefresh") // FID mode: onRegistered replaces onNewToken.
class SampleMessagingService : FirebaseMessagingService() {
    /** Forwards the Firebase Installation ID at startup, after register() and whenever it changes. */
    override fun onRegistered(installationId: String) = PushPilot.setPushToken(installationId)

    /** Tells PushPilot pushes apart from the app's other pushes. */
    override fun onMessageReceived(message: RemoteMessage) {
        if (PushPilot.handleMessage(message.data)) Log.d("PushPilotSample", "Received a PushPilot push")
    }
}
