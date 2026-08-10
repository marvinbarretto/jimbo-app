package dev.marvinbarretto.jimbo

import android.os.Bundle
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

        permissions.requestIfNeeded()
    }
}
