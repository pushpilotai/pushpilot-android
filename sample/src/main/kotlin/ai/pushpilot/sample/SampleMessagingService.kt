package ai.pushpilot.sample

import ai.pushpilot.sdk.PushPilot
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** The app's own FCM service; PushPilot never registers one. */
class SampleMessagingService : FirebaseMessagingService() {
    /** Forwards every new or rotated FCM token. */
    override fun onNewToken(token: String) = PushPilot.setPushToken(token)

    /** Tells PushPilot pushes apart from the app's other pushes. */
    override fun onMessageReceived(message: RemoteMessage) {
        if (PushPilot.handleMessage(message.data)) Log.d("PushPilotSample", "Received a PushPilot push")
    }
}
