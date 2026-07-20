package dev.marvinbarretto.jimbo.plugins

import androidx.health.connect.client.HealthConnectClient
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.HealthConnectReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

@CapacitorPlugin(name = "HealthSnapshot")
class HealthSnapshotPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PluginMethod
    fun getTodaySnapshot(call: PluginCall) {
        scope.launch {
            try {
                if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                    call.reject("HealthConnect not available on this device")
                    return@launch
                }
                val data = HealthConnectReader.readToday(context)
                val sleepMs = data.sleepSessions
                    .sumOf { (it.durationMinutes * 60_000).toLong() }

                val result = JSObject().apply {
                    put("steps", data.steps)
                    put("activeCalories", data.caloriesActive)
                    put("heartRateAvg", data.heartRateAvg ?: JSONObject.NULL)
                    put("heartRateMin", data.heartRateMin ?: JSONObject.NULL)
                    put("heartRateMax", data.heartRateMax ?: JSONObject.NULL)
                    put("sleepDurationMs", if (sleepMs > 0) sleepMs else JSONObject.NULL)
                    put("exerciseSessionCount", data.exerciseSessions.size)
                    put("asOf", System.currentTimeMillis())
                }
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("getTodaySnapshot failed: ${e.message}", e)
            }
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
