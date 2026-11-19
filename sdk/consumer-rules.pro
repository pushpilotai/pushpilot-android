# The in-app message bridge: the page calls PushPilotNative.postMessage by name.
-keepclassmembers class ai.pushpilot.sdk.inapp.** {
    @android.webkit.JavascriptInterface <methods>;
}
