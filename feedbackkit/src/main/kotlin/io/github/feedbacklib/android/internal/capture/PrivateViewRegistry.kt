package io.github.feedbacklib.android.internal.capture

import android.graphics.Rect
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Views and composables the host marked private (spec §5). Process-wide so that views can be
 * registered before build() and composables without reaching the runtime. Read on the main thread
 * at capture time; mutation is thread-safe.
 */
internal object PrivateViewRegistry {

    private class ComposeRegion(root: View, val rect: Rect) {
        // Weak: a strong reference here would keep the root view (and everything it holds,
        // e.g. an Activity) alive past its own lifetime if a composable never reaches onDetach.
        val root = WeakReference(root)
    }

    private val views = WeakHashMap<View, Unit>()
    private val composeRegions = WeakHashMap<Any, ComposeRegion>()

    @Synchronized
    fun add(vararg added: View) {
        added.forEach { views[it] = Unit }
    }

    @Synchronized
    fun remove(vararg removed: View) {
        removed.forEach { views.remove(it) }
    }

    @Synchronized
    fun updateCompose(key: Any, root: View, left: Int, top: Int, right: Int, bottom: Int) {
        composeRegions[key] = ComposeRegion(root, Rect(left, top, right, bottom))
    }

    @Synchronized
    fun removeCompose(key: Any) {
        composeRegions.remove(key)
    }

    /** Rectangles, in window coordinates, to black out in a capture of [decorView]'s window. */
    @Synchronized
    fun regionsFor(decorView: View): List<Rect> {
        val location = IntArray(2)
        val fromViews = views.keys
            .filter { it.isAttachedToWindow && it.isShown && it.rootView === decorView }
            .map { view ->
                view.getLocationInWindow(location)
                Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
        val fromCompose = composeRegions.values
            .mapNotNull { region -> region.root.get()?.let { root -> root to region } }
            .filter { (root, _) -> root.rootView === decorView }
            .map { (_, region) -> Rect(region.rect) }
        return fromViews + fromCompose
    }

    @Synchronized
    fun clearForTests() {
        views.clear()
        composeRegions.clear()
    }
}
