package dev.marvinbarretto.jimbo

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.getcapacitor.BridgeActivity
import com.getcapacitor.WebViewListener
import dev.marvinbarretto.jimbo.plugins.ActivityContextPlugin
import dev.marvinbarretto.jimbo.plugins.AuthPlugin
import dev.marvinbarretto.jimbo.place.GeofenceReceiver
import dev.marvinbarretto.jimbo.place.GymSessionState
import dev.marvinbarretto.jimbo.plugins.HealthSnapshotPlugin
import dev.marvinbarretto.jimbo.plugins.LocationContextPlugin
import dev.marvinbarretto.jimbo.plugins.NotificationTriggerPlugin
import dev.marvinbarretto.jimbo.plugins.TelemetryPlugin

class MainActivity : BridgeActivity() {

    // As launcher, the WebView shell owns the startup permission flow — a
    // fresh install must land the Health Connect / activity / location grants
    // or the collectors run dark. HomeActivity shares the same bootstrap.
    private val permissions = PermissionBootstrap(this)

    // /m/<tab> path from a tapped notification, held until the shell has loaded.
    private var pendingPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // All plugins must be registered before super.onCreate() so they're
        // available when the WebView loads and JS calls Capacitor.Plugins.<Name>.
        registerPlugin(TelemetryPlugin::class.java)
        registerPlugin(ActivityContextPlugin::class.java)
        registerPlugin(HealthSnapshotPlugin::class.java)
        registerPlugin(AuthPlugin::class.java)
        registerPlugin(NotificationTriggerPlugin::class.java)
        registerPlugin(LocationContextPlugin::class.java)
        super.onCreate(savedInstanceState)

        BridgeRegistry.getInstance(this).apply {
            registerCapability("telemetry", 1)
            registerCapability("activityContext", 1)
            registerCapability("healthSnapshot", 1)
            registerCapability("auth", 1)
            registerCapability("notification", 1)
            registerCapability("locationContext", 1)
            attachToBridge(bridge)
        }

        registerBackNavigation()
        registerNotificationDeepLink()
        handleNotificationIntent(intent)
        permissions.requestIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    /**
     * Opens `/m/<tab>` when the activity was launched by a notification tap.
     * Cold start: the shell isn't loaded yet, so the path waits for onPageLoaded.
     * Warm: the shell is up, so navigate straight away. The path is relative, so
     * it resolves against whichever origin the WebView loaded.
     */
    private fun handleNotificationIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(NotificationTriggerReceiver.EXTRA_TAB) ?: return
        intent.removeExtra(NotificationTriggerReceiver.EXTRA_TAB)
        if (!Regex("[a-z0-9-]+").matches(tab)) return
        val path = "/m/$tab"
        // The gym-arrival notification asks; this tap is the commit. Start the
        // session first so /m/train opens onto it.
        if (intent.getBooleanExtra(GeofenceReceiver.EXTRA_START_SESSION, false)) {
            intent.removeExtra(GeofenceReceiver.EXTRA_START_SESSION)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { GymSessionState.startIfNone() } }
                navigateTo(path)
            }
            return
        }
        navigateTo(path)
    }

    private fun navigateTo(path: String) {
        val webView = bridge?.webView
        if (webView != null && webView.url?.contains("/m") == true) {
            webView.evaluateJavascript("window.location.assign('$path')", null)
        } else {
            pendingPath = path
        }
    }

    private fun registerNotificationDeepLink() {
        bridge.addWebViewListener(object : WebViewListener() {
            override fun onPageLoaded(webView: WebView) {
                val path = pendingPath ?: return
                pendingPath = null
                webView.evaluateJavascript("window.location.assign('$path')", null)
            }
        })
    }

    /**
     * Makes the system back gesture step back through the shell instead of
     * killing the app.
     *
     * Capacitor 8's BridgeActivity registers no back handling at all — it was
     * moved out of core into @capacitor/app — so without this the default
     * dispatcher finishes the activity and a back swipe drops the user out of
     * Jimbo from however deep they'd navigated.
     *
     * Handled natively rather than via @capacitor/app because the WebView
     * loads a *remote* shell: native and web deploy independently, so a design
     * where the exit path depends on the shell having shipped a `backButton`
     * listener leaves a skew window (or a stale cached shell) in which back
     * does nothing and the app can't be dismissed at all. The else branch here
     * can't skew.
     *
     * The Angular router navigates with pushState, so its history entries are
     * WebView history entries — canGoBack/goBack walk the /m stack directly.
     *
     * Must run after super.onCreate(): `bridge` is created inside
     * BridgeActivity.onCreate, unlike registerPlugin which has to precede it.
     */
    private fun registerBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val webView = bridge?.webView
                    if (webView != null && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )
    }
}
