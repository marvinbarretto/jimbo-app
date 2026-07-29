package dev.marvinbarretto.jimbo.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the node-tree walker.
 *
 * [FakeNode] stands in for Android's AccessibilityNodeInfo — the whole reason
 * `dumpTree` is typed against the [NodeView] interface. Same move as testing
 * against a fake HTTP client rather than the network.
 */
class NodeTreeDumpTest {

    private class FakeNode(
        override val packageName: String? = INSTAGRAM_PACKAGE,
        override val className: String? = "android.widget.FrameLayout",
        override val viewId: String? = null,
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val isClickable: Boolean = false,
        private val children: List<NodeView?> = emptyList(),
    ) : NodeView {
        override val childCount: Int get() = children.size
        override fun child(index: Int): NodeView? = children[index]
    }

    /** Every test walks as Instagram unless it is specifically about the guard. */
    private fun dumpTree(root: NodeView?, limits: DumpLimits = DumpLimits()) =
        dumpTree(root, expectedPackage = INSTAGRAM_PACKAGE, limits = limits)

    @Test
    fun `a null root yields an empty dump rather than throwing`() {
        val dump = dumpTree(null)

        assertEquals(emptyList<String>(), dump.lines)
        assertEquals(0, dump.nodesVisited)
    }

    @Test
    fun `renders class, id, text and clickability`() {
        val dump = dumpTree(
            FakeNode(
                className = "android.widget.TextView",
                viewId = "com.instagram.android:id/row_feed_name",
                text = "thepleasance",
                isClickable = true,
            )
        )

        assertEquals(1, dump.lines.size)
        assertEquals("TextView id=row_feed_name text=\"thepleasance\" click=Y", dump.lines[0])
    }

    @Test
    fun `indents by depth`() {
        val dump = dumpTree(
            FakeNode(children = listOf(FakeNode(children = listOf(FakeNode(text = "deep")))))
        )

        assertTrue(dump.lines[0].startsWith("FrameLayout"))
        assertTrue(dump.lines[1].startsWith("  FrameLayout"))
        assertTrue(dump.lines[2].startsWith("    FrameLayout"))
    }

    @Test
    fun `counts text-bearing nodes including content descriptions`() {
        val dump = dumpTree(
            FakeNode(
                children = listOf(
                    FakeNode(text = "EXTRA SHOW"),
                    FakeNode(contentDescription = "Photo by thepleasance"),
                    FakeNode(),
                    // Blank text must not count as text-bearing — Instagram's tree is
                    // full of empty TextViews and they would drown the signal.
                    FakeNode(text = "   "),
                )
            )
        )

        assertEquals(5, dump.nodesVisited)
        assertEquals(2, dump.textBearingNodes)
    }

    @Test
    fun `flattens newlines so a multi-line caption stays one log line`() {
        val dump = dumpTree(FakeNode(text = "Joz Norris\n18:50\n6th Aug"))

        assertEquals(1, dump.lines.size)
        assertTrue(dump.lines[0].contains("text=\"Joz Norris 18:50 6th Aug\""))
    }

    @Test
    fun `truncates long text with an ellipsis`() {
        val dump = dumpTree(FakeNode(text = "x".repeat(200)), DumpLimits(maxTextChars = 10))

        assertTrue(dump.lines[0].contains("text=\"${"x".repeat(10)}…\""))
    }

    @Test
    fun `stops at the node limit and says so`() {
        val wide = FakeNode(children = List(50) { FakeNode(text = "n$it") })

        val dump = dumpTree(wide, DumpLimits(maxNodes = 10))

        assertEquals(10, dump.nodesVisited)
        assertTrue(dump.truncatedByNodeLimit)
        assertTrue(dump.summary().contains("TRUNCATED(node-limit)"))
    }

    @Test
    fun `stops at the depth limit and says so`() {
        // Chain of 6 nested nodes.
        var node = FakeNode(text = "leaf")
        repeat(5) { node = FakeNode(children = listOf(node)) }

        val dump = dumpTree(node, DumpLimits(maxDepth = 2))

        assertEquals(3, dump.nodesVisited) // depths 0, 1, 2
        assertTrue(dump.truncatedByDepthLimit)
        assertTrue(dump.summary().contains("TRUNCATED(depth-limit)"))
    }

    @Test
    fun `a stale null child is skipped rather than aborting the walk`() {
        val dump = dumpTree(
            FakeNode(
                children = listOf(
                    FakeNode(text = "before"),
                    null, // Instagram recycled the view mid-walk
                    FakeNode(text = "after"),
                )
            )
        )

        assertEquals(3, dump.nodesVisited)
        assertTrue(dump.lines.any { it.contains("before") })
        assertTrue(dump.lines.any { it.contains("after") })
    }

    @Test
    fun `an untruncated dump reports no drops`() {
        val dump = dumpTree(FakeNode(children = listOf(FakeNode(text = "a"))))

        assertFalse(dump.truncatedByNodeLimit)
        assertFalse(dump.truncatedByDepthLimit)
        assertEquals("nodes=2 text=1", dump.summary())
    }

    @Test
    fun `refuses to walk a window belonging to another app`() {
        // The 2026-07-29 leak, as a test: an Instagram event settled, but by the
        // time the walk ran the notification shade was the active window, so
        // rootInActiveWindow handed back SystemUI's tree — bank notifications and all.
        val shade = FakeNode(
            packageName = "com.android.systemui",
            children = listOf(
                FakeNode(
                    packageName = "com.android.systemui",
                    text = "A/C_1511 You're using your arranged overdraft.",
                ),
            ),
        )

        val dump = dumpTree(shade, expectedPackage = INSTAGRAM_PACKAGE)

        assertEquals(emptyList<String>(), dump.lines)
        assertEquals(0, dump.nodesVisited)
        assertEquals("com.android.systemui", dump.rejectedPackage)
        assertTrue(dump.summary().contains("REFUSED"))
    }

    @Test
    fun `refuses a root with no package rather than assuming it is ours`() {
        val dump = dumpTree(FakeNode(packageName = null, text = "unattributed"), expectedPackage = INSTAGRAM_PACKAGE)

        assertEquals(emptyList<String>(), dump.lines)
        assertEquals("unknown", dump.rejectedPackage)
    }

    @Test
    fun `walks normally when the window really is instagram`() {
        val dump = dumpTree(FakeNode(text = "caption"), expectedPackage = INSTAGRAM_PACKAGE)

        assertEquals(1, dump.nodesVisited)
        assertNull(dump.rejectedPackage)
    }

    @Test
    fun `missing class name renders as a placeholder`() {
        val dump = dumpTree(FakeNode(className = null, text = "orphan"))

        assertTrue(dump.lines[0].startsWith("? "))
    }
}
