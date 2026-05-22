package dev.marvinbarretto.jimbo.cap

import android.os.Bundle
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.getcapacitor.BridgeActivity
import kotlinx.coroutines.launch

class MainActivity : BridgeActivity() {

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            // Kick a manual sync so GymSessionBridge runs immediately rather than
            // waiting for the next periodic tick.
            SyncScheduler.enqueueManualSync(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WorkManager handles its own scheduling persistence; UPDATE policy makes this
        // a cheap re-arm on every launch. BootReceiver also calls this after reboot.
        SyncScheduler.schedulePeriodic(this)
        SyncScheduler.enqueueManualSync(this)

        lifecycleScope.launch {
            if (HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE) {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()
                if (!granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                    requestHealthPermissions.launch(HealthConnectReader.PERMISSIONS)
                }
            }
        }
    }
}
