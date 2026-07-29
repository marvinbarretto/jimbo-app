package dev.marvinbarretto.jimbo.telemetry

import android.view.accessibility.AccessibilityNodeInfo

/**
 * AccessibilityNodeView — adapts Android's [AccessibilityNodeInfo] to [NodeView].
 *
 * WHY THIS EXISTS
 * One tiny file whose only job is to be the seam between Android and the pure
 * traversal logic in `NodeTreeDump.kt`. Same idea as wrapping `fetch` behind an
 * interface so the code under test never touches the network: because the walker
 * is typed against [NodeView] rather than the Android class, it runs under plain
 * JUnit on the JVM, and everything Android-specific is quarantined here.
 *
 * A note on `recycle()`: older Android required manually recycling every node you
 * touched, like freeing memory in C. Deprecated and a no-op since API 33, and this
 * module's minSdk is 34, so there is nothing to release.
 */
internal class AccessibilityNodeView(
    private val node: AccessibilityNodeInfo,
) : NodeView {

    override val packageName: String?
        get() = node.packageName?.toString()

    override val className: String?
        get() = node.className?.toString()

    override val viewId: String?
        get() = node.viewIdResourceName

    override val text: String?
        get() = node.text?.toString()

    override val contentDescription: String?
        get() = node.contentDescription?.toString()

    override val isClickable: Boolean
        get() = node.isClickable

    override val childCount: Int
        get() = node.childCount

    /**
     * Each read is an IPC hop into Instagram's process, and the view may have been
     * recycled since the parent was read — both surface as an exception or a null.
     * Callers treat null as "skip this subtree", never as a reason to abort.
     */
    override fun child(index: Int): NodeView? = try {
        node.getChild(index)?.let { AccessibilityNodeView(it) }
    } catch (_: Exception) {
        null
    }
}
