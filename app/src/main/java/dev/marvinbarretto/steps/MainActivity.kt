package dev.marvinbarretto.steps

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The only screen in the app. Its job:
 * 1. Request Health Connect permissions (one-time)
 * 2. Schedule the hourly background sync via WorkManager
 * 3. Run an initial 30-day backfill so the dashboard isn't empty
 *
 * After first launch, you never need to open this app again —
 * WorkManager handles everything in the background.
 *
 * There's no UI framework here (no Compose, no XML layouts).
 * Just a single TextView created in code. The app is invisible
 * infrastructure, not something you interact with.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView

    // Register the permission request callback.
    // This is Android's equivalent of a Promise that resolves when
    // the user responds to the permission dialog.
    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
            statusText.text = "Permissions granted. Syncing..."
            scheduleSync()
            runInitialSync()
        } else {
            statusText.text = "Permissions denied. Open Health Connect settings to grant access."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create a simple text view programmatically — no XML layout needed
        statusText = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
        }
        setContentView(statusText)

        // Check if Health Connect is available on this device
        val available = HealthConnectClient.getSdkStatus(this)
        android.util.Log.d("StepsSync", "Health Connect SDK status: $available (need ${HealthConnectClient.SDK_AVAILABLE})")
        statusText.text = "Checking Health Connect..."

        if (available != HealthConnectClient.SDK_AVAILABLE) {
            statusText.text = "Health Connect not available (status=$available)."
            android.util.Log.e("StepsSync", "Health Connect not available: $available")
            return
        }

        // lifecycleScope.launch is Kotlin's way of running async code
        // (similar to wrapping something in an async IIFE in JS)
        lifecycleScope.launch {
            try {
                android.util.Log.d("StepsSync", "Checking permissions...")
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()
                android.util.Log.d("StepsSync", "Granted permissions: $granted")

                if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                    // Already have permissions — schedule background sync
                    // and run initial backfill (dedup on Jimbo means this is safe to repeat)
                    statusText.text = "Permissions OK. Syncing..."
                    android.util.Log.d("StepsSync", "All permissions granted, starting sync")
                    scheduleSync()
                    runInitialSync()
                } else {
                    // First launch — ask for permissions.
                    // This opens a system dialog listing what data we want to read.
                    android.util.Log.d("StepsSync", "Missing permissions, requesting...")
                    requestPermissions.launch(HealthConnectReader.PERMISSIONS)
                }
            } catch (e: Exception) {
                android.util.Log.e("StepsSync", "Error in permission check", e)
                statusText.text = "Error: ${e.message}"
            }
        }
    }

    /**
     * Schedule the hourly background sync.
     * KEEP policy means if a sync is already scheduled, don't replace it.
     *
     * Constraints ensure we only sync when:
     * - Network is available (no point trying without internet)
     * - Battery isn't low (be a good citizen)
     */
    private fun scheduleSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            1, TimeUnit.HOURS,     // repeat every hour
            15, TimeUnit.MINUTES   // flex window — can run 15min early
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "steps_sync",                       // unique name prevents duplicates
            ExistingPeriodicWorkPolicy.KEEP,     // don't replace existing schedule
            request
        )
    }

    /**
     * Pull the last 30 days of data from Health Connect and POST to Jimbo.
     * This only runs on first launch so the dashboard has historical data.
     * Dedup on Jimbo's side means re-running this is harmless.
     */
    private fun runInitialSync() {
        lifecycleScope.launch {
            try {
                val end = Instant.now()
                val start = end.minus(Duration.ofDays(30))

                android.util.Log.d("StepsSync", "Initial sync: $start to $end")
                statusText.text = "Reading Health Connect (30 days)..."
                val payload = HealthConnectReader.readAll(this@MainActivity, start, end)
                val count = payload.getJSONArray("records").length()
                android.util.Log.d("StepsSync", "Read $count records from Health Connect")

                if (count == 0) {
                    android.util.Log.w("StepsSync", "No records found — Health Connect may be empty")
                    statusText.text = "No records found in Health Connect.\nDo you have Google Fit or similar installed?"
                    return@launch
                }

                statusText.text = "Sending $count records to Jimbo..."
                android.util.Log.d("StepsSync", "POSTing $count records to Jimbo...")
                val (code, body) = JimboClient.postSync(payload.toString())
                android.util.Log.d("StepsSync", "Jimbo response: $code — $body")

                statusText.text = if (code in 200..299) {
                    "Done! Synced $count records.\nBackground sync active.\nYou can close this app."
                } else {
                    "Sync failed (HTTP $code):\n$body"
                }
            } catch (e: Exception) {
                statusText.text = "Error: ${e.message}"
            }
        }
    }
}
