package ai.pushpilot.sdk.inapp

import ai.pushpilot.sdk.log
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

/** The fixed base URL pages load under (section 4); `.invalid` never resolves. */
internal const val BASE_URL = "https://in-app.pushpilot.invalid/"

/**
 * The full-screen transparent, locked-down web view over [activity] (section 4), added to its decor
 * view. Page messages, back presses and a crashed renderer all reach [onMessage] on the main thread;
 * a back press or crash arrives as `close`. Main thread only.
 */
internal class Overlay private constructor(
    val activity: Activity,
    private val web: WebView,
    private val onMessage: (type: String, value: String) -> Unit,
) {
    private var backCallback: Any? = null

    /** Receives `PushPilotNative.postMessage(json)` on a WebView thread and hands it to the main thread. */
    private inner class Bridge {
        /** The page's one way to talk to the SDK: `{"type": …, "value": …}`. */
        @JavascriptInterface
        fun postMessage(json: String) {
            val message = try { JSONObject(json) } catch (e: Exception) { return }
            val type = message.optString("type")
            val value = if (message.isNull("value")) "" else message.optString("value")
            main.post { onMessage(type, value) }
        }
    }

    /** Takes the web view off the screen and frees it. */
    fun remove() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    /** Plays the haptic for [kind]; unknown kinds do nothing. Follows the user's touch-feedback setting. */
    fun haptic(kind: String) {
        hapticConstant(kind, Build.VERSION.SDK_INT)?.let { web.performHapticFeedback(it) }
    }

    /** Sets up the web view, the bridge, back handling and posture updates, then loads [html]. */
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun start(html: String, density: Float) {
        lockDown(web)
        web.webViewClient = object : WebViewClient() {
            /** Cancels every navigation; links go through `pushpilot.open`. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            /** The same for Android 6, which calls only this overload. */
            @Deprecated("Android 6 only")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = true

            /** A crashed or killed renderer would otherwise take the app down with it; close instead. */
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                log("in-app renderer gone; closing the message")
                main.post { onMessage("close", "") }
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "PushPilotNative")
        web.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) onMessage("close", "")
            keyCode == KeyEvent.KEYCODE_BACK
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { onMessage("close", "") }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
            backCallback = callback
        }
        // Posture (section 9): always flat on Android, so only the screen size changes.
        web.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            val oldWidth = oldRight - oldLeft
            val oldHeight = oldBottom - oldTop
            if ((oldWidth != 0 || oldHeight != 0) && (width != oldWidth || height != oldHeight)) {
                web.evaluateJavascript(postureUpdate(posture(width / density.toDouble(), height / density.toDouble())), null as ValueCallback<String>?)
            }
        }
        (activity.window.decorView as ViewGroup).addView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.requestFocus()
        web.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        /**
         * Shows the page [build] makes from the window's insets and posture over [activity]; null when
         * the window is not ready or the device's WebView cannot be created (being updated, missing).
         */
        fun show(activity: Activity, build: (Insets, JSONObject) -> String, onMessage: (String, String) -> Unit): Overlay? = try {
            val decor = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
            val height = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
            val html = build(insets(decor, density), posture(width / density.toDouble(), height / density.toDouble()))
            Overlay(activity, WebView(activity), onMessage).also { it.start(html, density) }
        } catch (e: Throwable) {
            log("in-app message not shown: ${e.javaClass.simpleName}")
            null
        }

        /** Every setting section 4 asks for: JavaScript only, no storage, files, location, zoom, windows or autofill. */
        @SuppressLint("SetJavaScriptEnabled")
        @Suppress("DEPRECATION")
        private fun lockDown(web: WebView) {
            web.settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                domStorageEnabled = false
                databaseEnabled = false
                setGeolocationEnabled(false)
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                saveFormData = false
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            web.webChromeClient = object : WebChromeClient() {
                /** Camera and microphone are never granted. */
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()

                /** Location is never granted. */
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, false, false)
                }
            }
            if (Build.VERSION.SDK_INT >= 26) web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            web.setBackgroundColor(Color.TRANSPARENT)
            web.overScrollMode = View.OVER_SCROLL_NEVER
            web.isVerticalScrollBarEnabled = false
            web.isHorizontalScrollBarEnabled = false
            web.isFocusable = true
            web.isFocusableInTouchMode = true
        }

        /** The window's system-bar and cutout insets in CSS px; zero before the window has insets. */
        @Suppress("DEPRECATION")
        private fun insets(decor: View, density: Float): Insets {
            val window = decor.rootWindowInsets ?: return Insets(0.0, 0.0, 0.0, 0.0)
            var top: Int
            var right: Int
            var bottom: Int
            var left: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = window.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = i.top; right = i.right; bottom = i.bottom; left = i.left
            } else {
                top = window.systemWindowInsetTop; right = window.systemWindowInsetRight
                bottom = window.systemWindowInsetBottom; left = window.systemWindowInsetLeft
                if (Build.VERSION.SDK_INT >= 28) window.displayCutout?.let {
                    top = maxOf(top, it.safeInsetTop); right = maxOf(right, it.safeInsetRight)
                    bottom = maxOf(bottom, it.safeInsetBottom); left = maxOf(left, it.safeInsetLeft)
                }
            }
            val d = density.toDouble()
            return Insets(top / d, right / d, bottom / d, left / d)
        }
    }
}

/** The `View.performHapticFeedback` constant for a `pushpilot.haptic` kind on API [sdk]; null for unknown kinds. */
@SuppressLint("InlinedApi") // each newer constant is used only when [sdk] (Build.VERSION.SDK_INT) has it
internal fun hapticConstant(kind: String, sdk: Int): Int? = when (kind) {
    "light" -> HapticFeedbackConstants.KEYBOARD_TAP
    "medium" -> HapticFeedbackConstants.VIRTUAL_KEY
    "heavy" -> HapticFeedbackConstants.LONG_PRESS
    "selection" -> if (sdk >= 27) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.CLOCK_TICK
    "success" -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    "warning" -> HapticFeedbackConstants.LONG_PRESS
    "error" -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    else -> null
}
