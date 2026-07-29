package dev.marvinbarretto.jimbo.telemetry

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.marvinbarretto.jimbo.BuildConfig
import dev.marvinbarretto.jimbo.ScreenSyncWorker
import dev.marvinbarretto.jimbo.data.ScreenPostEntity
import dev.marvinbarretto.jimbo.data.StepsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * InstagramScreenService — the Instagram screen-capture channel.
 *
 * WHAT IT DOES TODAY
 * Watches Instagram's accessibility event stream, waits for the human to stop
 * scrolling, then reads the visible posts out of the node tree and queues them
 * for LocalShout. Metadata only — handle, kind, location, date, and whatever
 * caption text Instagram exposes. **No screenshots**: `canTakeScreenshots` is
 * not in the service config, so the OS would refuse one.
 *
 * WHY METADATA ONLY, MEASURED
 * Captions are a weak lane and the numbers say so. Of the captions Instagram
 * renders, ~51% are collapsed behind "… more" (median 74 chars) and expanding
 * one needs a tap, which the posture forbids. Stories carry no caption at all —
 * their content is purely pixels. Instagram's own image OCR is absent from the
 * tree (4 of 212 posts, and those were "Photo by X, N likes" attributions, not
 * OCR). So text is a bonus; the image is the real payload, and capturing it is
 * a separate, deliberately-gated step.
 *
 * WHAT AN AccessibilityService IS (concept mapping)
 * - It is a **system-level event listener**, closest thing in web-land to a
 *   `document.addEventListener` that the browser installs for you *across every
 *   page* — except the user must explicitly grant it in Settings → Accessibility,
 *   and the OS, not us, decides which apps' events reach the callback.
 * - `res/xml/instagram_screen_service.xml` is the **subscription declaration**:
 *   which packages, which event types, how aggressively to coalesce. Think of it
 *   as the options object you'd pass to `addEventListener` — but read by the OS at
 *   bind time, so it is a *capability boundary*, not a runtime filter we could bug.
 * - `onAccessibilityEvent` is the callback. It runs on the **main thread**, exactly
 *   like a DOM handler — so it must stay cheap. Anything slow (M2's tree walk,
 *   M4's screenshot) goes onto a coroutine, never inline here.
 * - `onServiceConnected` / `onUnbind` are the lifecycle pair — `ngOnInit` /
 *   `ngOnDestroy`. Timers armed in the first must be torn down in the second or
 *   they leak past the service's life.
 *
 * DEBOUNCING, ANDROID-STYLE
 * There is no `setTimeout`. The equivalent is a `Handler` bound to a `Looper`
 * (a message queue for a thread) plus `postDelayed`. Re-arming a debounce is
 * `removeCallbacks(runnable)` then `postDelayed(runnable, delay)` — the same
 * clearTimeout/setTimeout pair, spelled differently. Two timers run here:
 *   - settle (~700ms) — the human stopped scrolling
 *   - idle (~30s)     — the human left Instagram
 * The decision of what a fired timer *means* lives in [InstagramScreenTracker],
 * which is pure and unit-tested; this class only owns the wiring.
 *
 * POSTURE
 * Scoped by the OS to `com.instagram.android` alone, on Marvin's own device,
 * observing his own normal usage. It never injects input — nothing scrolls by
 * itself. See docs/plans/2026-07-29-jimbo-app-screen-capture-spec.md (LocalShout).
 */
private const val TAG = "JimboScreen"

/**
 * Re-enter M2a observation mode: log the whole node tree instead of harvested
 * posts. Flip this on when Instagram ships an update and the harvest goes empty —
 * the structure is the thing you need to see at that point, not the results.
 */
private const val DUMP_RAW_TREE = false

class InstagramScreenService : AccessibilityService() {

    // Bound to the main looper because that is the thread onAccessibilityEvent
    // arrives on — posting from and to the same thread means no synchronisation
    // is needed around the tracker's mutable state.
    private val handler = Handler(Looper.getMainLooper())

    // Background scope for tree walks. SupervisorJob so one failed walk does not
    // tear down the scope — closest analogue is an unhandled rejection in one
    // promise not killing the others.
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tracker = InstagramScreenTracker()

    private val settleRunnable = Runnable {
        val report = tracker.onSettleDeadline(System.currentTimeMillis()) ?: return@Runnable
        // The burst is closed; its ceiling timer is now moot.
        handler.removeCallbacks(churnRunnable)
        logSettle(report)
    }

    // Armed once when a burst opens and never re-armed by later events — a ceiling,
    // not a debounce. Without it, a continuously-updating screen (video, fast fling)
    // never goes quiet and so never harvests at all. See DEFAULT_MAX_BURST_MS.
    private val churnRunnable = Runnable {
        val report = tracker.onChurnDeadline(System.currentTimeMillis()) ?: return@Runnable
        logSettle(report)
    }

    private fun logSettle(report: SettleReport) {
        Log.d(
            TAG,
            "settle session=${report.sessionId} " +
                "kind=${if (report.forced) "forced-churn" else "quiet"} " +
                "burst=${report.burstDurationMs}ms " +
                "window=${report.windowChanges} content=${report.contentChanges}"
        )
        dumpTreeForObservation(report)
    }

    /**
     * M2b: harvest posts from the settled tree and log what we got.
     *
     * Set [DUMP_RAW_TREE] to re-enter M2a observation mode — a full structural
     * dump, needed whenever Instagram changes and the harvester stops matching.
     * It is off by default because it prints every text node on screen; the
     * harvest logs only post-shaped content, which is both quieter and narrower.
     */
    private fun dumpTreeForObservation(report: SettleReport) {
        if (!BuildConfig.DEBUG) return

        // The walk is dozens-to-hundreds of IPC calls into Instagram's process.
        // onAccessibilityEvent and the timers all run on the main thread; doing this
        // there would jank the phone he is holding. Hence the IO dispatcher.
        scope.launch {
            val root = try {
                rootInActiveWindow
            } catch (e: Exception) {
                Log.w(TAG, "tree unavailable for session=${report.sessionId}: ${e.message}")
                return@launch
            }

            if (root == null) {
                // Expected sometimes — the window can go away between settling and walking.
                Log.d(TAG, "tree session=${report.sessionId} — no active window root")
                return@launch
            }

            val node = AccessibilityNodeView(root)

            if (DUMP_RAW_TREE) {
                val dump = dumpTree(node, expectedPackage = INSTAGRAM_PACKAGE)
                Log.d(TAG, "tree session=${report.sessionId} ${dump.summary()}")
                if (dump.rejectedPackage != null) return@launch
                // One node per line: logcat truncates around 4k per entry, and a
                // feed tree easily exceeds that as a single blob.
                dump.lines.forEach { Log.d(TAG, it) }
                return@launch
            }

            val posts = harvestPosts(node)
            if (posts.isEmpty()) {
                Log.d(TAG, "harvest session=${report.sessionId} — no posts on screen")
                return@launch
            }

            enqueue(posts)

            Log.d(TAG, "harvest session=${report.sessionId} posts=${posts.size}")
            posts.forEach { post ->
                Log.d(
                    TAG,
                    "  post ${post.contentHash} @${post.handle} kind=${post.kind.wireValue()} " +
                        "when=\"${post.postedAtLabel}\" " +
                        "loc=${post.locationName ?: "-"} " +
                        "caption=${captionState(post)} " +
                        "alt=${if (post.altText != null) "YES(${post.altText!!.length})" else "no"}"
                )
                post.altText?.let { Log.d(TAG, "    alt: $it") }
                post.caption?.let { Log.d(TAG, "    cap: $it") }
            }
        }
    }

    /**
     * Queue harvested posts for LocalShout.
     *
     * The DAO's primary key is the content hash with an IGNORE conflict policy,
     * so re-harvesting a post that is still on screen is a no-op at the database
     * level — no "have I already got this?" check is needed here.
     */
    private suspend fun enqueue(posts: List<HarvestedPost>) {
        val dao = StepsDatabase.getInstance(applicationContext).screenPostDao()
        val now = System.currentTimeMillis()
        dao.insertAll(
            posts.map { post ->
                ScreenPostEntity(
                    contentHash = post.contentHash,
                    handle = post.handle,
                    kindHint = post.kind.wireValue(),
                    capturedAt = now,
                    caption = post.caption,
                    captionTruncated = post.captionTruncated,
                    altText = post.altText,
                    locationName = post.locationName,
                    postedAtLabel = post.postedAtLabel,
                )
            }
        )
    }

    /** Whether the caption is usable, a stub, or absent — the M2a headline finding. */
    private fun captionState(post: HarvestedPost): String = when {
        post.caption == null -> "none"
        post.captionTruncated -> "TRUNCATED(${post.caption!!.length})"
        else -> "full(${post.caption!!.length})"
    }

    private val idleRunnable = Runnable {
        val report = tracker.onIdleDeadline(System.currentTimeMillis()) ?: return@Runnable
        Log.i(
            TAG,
            "instagram background — session=${report.sessionId} " +
                "duration=${report.durationMs}ms " +
                "window=${report.windowChanges} content=${report.contentChanges} " +
                "settles=${report.settles}"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(
            TAG,
            "screen service connected — watching $INSTAGRAM_PACKAGE only, " +
                "tree dump ${if (BuildConfig.DEBUG) "ON (M2a observation)" else "off"}"
        )
        // Arm the queue drain here rather than in MainActivity: the service can
        // be running (and capturing) without the app UI ever being opened.
        ScreenSyncWorker.schedule(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val windowStateChanged = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> false
            // The XML subscribes to exactly those two types; anything else means
            // the config and this code have drifted apart.
            else -> {
                Log.w(TAG, "unexpected event type ${event.eventType} — check instagram_screen_service.xml")
                return
            }
        }

        val outcome = tracker.onEvent(
            packageName = event.packageName?.toString(),
            windowStateChanged = windowStateChanged,
            nowMs = System.currentTimeMillis(),
        )

        if (!outcome.accepted) {
            Log.w(TAG, "dropped event from ${event.packageName} — not $INSTAGRAM_PACKAGE")
            return
        }

        if (outcome.sessionStarted) {
            Log.i(TAG, "instagram foreground — session=${outcome.sessionId}")
        }

        // Re-arm both debounces. Every event pushes the settle and idle deadlines
        // further out; they only fire once the stream genuinely goes quiet.
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, DEFAULT_SETTLE_DELAY_MS)
        handler.removeCallbacks(idleRunnable)
        handler.postDelayed(idleRunnable, DEFAULT_IDLE_TIMEOUT_MS)

        // The churn ceiling is deliberately NOT re-armed per event — arming it only
        // when a burst opens is what makes it a ceiling rather than a third debounce.
        if (outcome.burstStarted) {
            handler.postDelayed(churnRunnable, DEFAULT_MAX_BURST_MS)
        }
    }

    /**
     * The system calls this when it needs us to abandon whatever we were doing —
     * e.g. another accessibility service took over feedback. Nothing to abort in
     * M1; from M4 this is where an in-flight screenshot would be cancelled.
     */
    override fun onInterrupt() {
        Log.d(TAG, "interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "screen service unbound")
        handler.removeCallbacks(settleRunnable)
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(churnRunnable)
        tracker.reset()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
