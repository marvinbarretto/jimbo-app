package dev.marvinbarretto.jimbo.telemetry

/**
 * NodeTreeDump — renders an accessibility node tree as readable, greppable lines.
 *
 * PURPOSE (M2a — observation, not harvesting)
 * Before writing a TextHarvester we need to know what Instagram's tree actually
 * contains. The spec's own top risk is that IG's view IDs are obfuscated and shift
 * with app updates, and M1 proved that window-state events carry no navigation
 * signal — so post kind, account handle and caption all have to come from here.
 * Guessing at that structure and writing a classifier against the guess is the
 * reliable way to waste the milestone. So: dump first, classify second.
 *
 * CONCEPT MAPPING (JS / Angular)
 * - An accessibility node tree is the app's **DOM**. Each node is an element:
 *   a class name (`android.widget.TextView` ≈ tag name), an optional view id
 *   (≈ `id` attribute, but obfuscated in release builds), `text` (≈ textContent),
 *   `contentDescription` (≈ aria-label), and children.
 * - [NodeView] is a **structural interface** over that tree — the same trick as
 *   typing a function against `{ children: Node[] }` instead of a real DOM node,
 *   so tests can pass a plain object. The real Android type is adapted in
 *   `AccessibilityNodeView`; this file imports nothing from Android, which is what
 *   keeps it JVM-testable.
 * - Reading a node is an **IPC call into Instagram's process** — closer to `fetch`
 *   than to a property read. Hundreds of them add up, hence the hard limits below,
 *   and why the walk never runs on the main thread.
 */

/** Structural view of one node. Implemented for real by `AccessibilityNodeView`. */
internal interface NodeView {
    /** Owning app. The root's value is the only thing that authorises a walk — see [dumpTree]. */
    val packageName: String?
    val className: String?
    val viewId: String?
    val text: String?
    val contentDescription: String?
    val isClickable: Boolean
    val childCount: Int
    fun child(index: Int): NodeView?
}

/**
 * Bounds on a dump. Instagram's feed tree is large and every node read is an IPC
 * hop, so the walk is capped rather than exhaustive — a truncated dump that
 * arrives is worth more than a complete one that janks the phone.
 */
internal data class DumpLimits(
    val maxNodes: Int = 400,
    val maxDepth: Int = 25,
    /**
     * 2200 = Instagram's own caption ceiling, so this never truncates a caption.
     *
     * It was 120 in the first M2a build, which turned out to be actively
     * misleading: 15 of 49 captures hit our own cap, and could not be told apart
     * from the ones Instagram itself had collapsed to "… more". Since the whole
     * point of the milestone is measuring how much caption text we really get,
     * our limit has to sit above Instagram's or it contaminates the measurement.
     * Still under logcat's ~4000-char-per-entry limit.
     */
    val maxTextChars: Int = 2200,
)

/** Outcome of a walk: the rendered lines plus what we had to leave out. */
internal data class TreeDump(
    val lines: List<String>,
    val nodesVisited: Int,
    val textBearingNodes: Int,
    val truncatedByNodeLimit: Boolean,
    val truncatedByDepthLimit: Boolean,
    /** Non-null when the walk was refused because the window belonged to another app. */
    val rejectedPackage: String? = null,
) {
    /** One-line headline for the log, so drops are visible rather than silent. */
    fun summary(): String = buildString {
        if (rejectedPackage != null) {
            append("REFUSED — active window belongs to $rejectedPackage")
            return@buildString
        }
        append("nodes=$nodesVisited text=$textBearingNodes")
        if (truncatedByNodeLimit) append(" TRUNCATED(node-limit)")
        if (truncatedByDepthLimit) append(" TRUNCATED(depth-limit)")
    }
}

/**
 * Walk [root] depth-first and render it. Pure: no logging, no Android, no clock —
 * it takes a tree and returns strings, which is what makes it testable.
 *
 * REFUSES to walk unless the root belongs to [expectedPackage]. This is not
 * defensive padding — it is load-bearing, and it was added after a real leak.
 *
 * The manifest's `packageNames` filter constrains which *events* the OS delivers.
 * It says nothing about `rootInActiveWindow`, which returns whatever window is
 * active when you ask. Because the walk is debounced and runs off the main thread,
 * the user can pull down the notification shade in the gap — and on 2026-07-29
 * that is exactly what happened, capturing bank and messaging notifications into
 * a debug log. The event filter was never the whole guarantee; this is the
 * other half of it, and it belongs here in the pure layer where a test can hold
 * it in place rather than in a stray `if` at the call site.
 */
internal fun dumpTree(
    root: NodeView?,
    expectedPackage: String,
    limits: DumpLimits = DumpLimits(),
): TreeDump {
    if (root == null) {
        return TreeDump(emptyList(), 0, 0, false, false)
    }

    val actualPackage = root.packageName
    if (actualPackage != expectedPackage) {
        return TreeDump(
            lines = emptyList(),
            nodesVisited = 0,
            textBearingNodes = 0,
            truncatedByNodeLimit = false,
            truncatedByDepthLimit = false,
            rejectedPackage = actualPackage ?: "unknown",
        )
    }

    val lines = mutableListOf<String>()
    var visited = 0
    var textBearing = 0
    var hitNodeLimit = false
    var hitDepthLimit = false

    fun walk(node: NodeView, depth: Int) {
        if (visited >= limits.maxNodes) {
            hitNodeLimit = true
            return
        }
        if (depth > limits.maxDepth) {
            hitDepthLimit = true
            return
        }

        visited++
        val text = node.text?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.takeIf { it.isNotBlank() }
        if (text != null || desc != null) textBearing++

        lines += renderNode(node, depth, text, desc, limits.maxTextChars)

        for (i in 0 until node.childCount) {
            if (visited >= limits.maxNodes) {
                hitNodeLimit = true
                return
            }
            // A null child is normal: the node can go stale mid-walk if Instagram
            // recycles the view while we are reading it. Skip, don't abort.
            walk(node.child(i) ?: continue, depth + 1)
        }
    }

    walk(root, 0)

    return TreeDump(
        lines = lines,
        nodesVisited = visited,
        textBearingNodes = textBearing,
        truncatedByNodeLimit = hitNodeLimit,
        truncatedByDepthLimit = hitDepthLimit,
    )
}

private fun renderNode(
    node: NodeView,
    depth: Int,
    text: String?,
    desc: String?,
    maxTextChars: Int,
): String = buildString {
    append("  ".repeat(depth))
    append(shortClassName(node.className))
    node.viewId?.takeIf { it.isNotBlank() }?.let { append(" id=").append(shortViewId(it)) }
    text?.let { append(" text=\"").append(truncate(it, maxTextChars)).append('"') }
    desc?.let { append(" desc=\"").append(truncate(it, maxTextChars)).append('"') }
    if (node.isClickable) append(" click=Y")
}

/** `android.widget.TextView` → `TextView`. The package prefix is noise at scale. */
internal fun shortClassName(className: String?): String =
    className?.substringAfterLast('.')?.takeIf { it.isNotBlank() } ?: "?"

/** `com.instagram.android:id/row_feed_name` → `row_feed_name`. */
internal fun shortViewId(viewId: String): String = viewId.substringAfterLast('/')

/** Collapse newlines so one node stays one log line — captions are multi-line. */
internal fun truncate(value: String, max: Int): String {
    val flat = value.replace(Regex("\\s+"), " ").trim()
    return if (flat.length <= max) flat else flat.take(max) + "…"
}
