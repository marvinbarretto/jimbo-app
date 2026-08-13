package dev.marvinbarretto.jimbo

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.getcapacitor.BridgeActivity
import dev.marvinbarretto.jimbo.plugins.ActivityContextPlugin
import dev.marvinbarretto.jimbo.plugins.AuthPlugin
import dev.marvinbarretto.jimbo.plugins.HealthSnapshotPlugin
import dev.marvinbarretto.jimbo.plugins.TelemetryPlugin

class MainActivity : BridgeActivity() {

    // As launcher, the WebView shell owns the startup permission flow — a
    // fresh install must land the Health Connect / activity / location grants
    // or the collectors run dark. HomeActivity shares the same bootstrap.
    private val permissions = PermissionBootstrap(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // All plugins must be registered before super.onCreate() so they're
        // available when the WebView loads and JS calls Capacitor.Plugins.<Name>.
        registerPlugin(TelemetryPlugin::class.java)
        registerPlugin(ActivityContextPlugin::class.java)
        registerPlugin(HealthSnapshotPlugin::class.java)
        registerPlugin(AuthPlugin::class.java)
        super.onCreate(savedInstanceState)

        BridgeRegistry.getInstance(this).apply {
            registerCapability("telemetry", 1)
            registerCapability("activityContext", 1)
            registerCapability("healthSnapshot", 1)
            registerCapability("auth", 1)
            attachToBridge(bridge)
        }

        registerBackNavigation()
        permissions.requestIfNeeded()
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
