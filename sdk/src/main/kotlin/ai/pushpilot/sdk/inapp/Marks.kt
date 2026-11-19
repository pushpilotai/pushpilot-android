package ai.pushpilot.sdk.inapp

import android.app.Activity
import android.graphics.Rect
import android.view.View
import java.lang.ref.WeakReference

/** Views the app named for coach marks (section 6), held weakly so a marked screen can still be freed. */
internal object Marks {
    private val views = HashMap<String, WeakReference<View>>()

    /** Names [view]; a later mark with the same name replaces it. */
    @Synchronized
    fun mark(view: View, name: String) {
        views[name] = WeakReference(view)
    }

    /** Forgets the view named [name]. */
    @Synchronized
    fun unmark(name: String) {
        views.remove(name)
    }

    /**
     * The frame of the view named [name] in [activity]'s window, in CSS px; null unless it is still
     * alive, attached to that window, shown, laid out and at least partly on screen. Main thread only.
     */
    @Synchronized
    fun frame(name: String, activity: Activity): Frame? {
        val view = views[name]?.get() ?: return null.also { views.remove(name) }
        if (!view.isAttachedToWindow || !view.isShown || view.rootView !== activity.window.decorView) return null
        if (view.width == 0 || view.height == 0 || !view.getGlobalVisibleRect(Rect())) return null
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return cssFrame(location[0], location[1], view.width, view.height, view.resources.displayMetrics.density)
    }
}
