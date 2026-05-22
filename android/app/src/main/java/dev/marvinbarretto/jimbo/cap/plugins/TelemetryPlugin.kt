package dev.marvinbarretto.jimbo.cap.plugins

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.cap.data.StepsDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

@CapacitorPlugin(name = "Telemetry")
class TelemetryPlugin : Plugin() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @PluginMethod
    fun getSyncStatus(call: PluginCall) {
        scope.launch {
            try {
                val eventDao = StepsDatabase.getInstance(context).eventDao()
                val result = JSObject().apply {
                    val lastSyncAt = eventDao.lastSyncedAt()
                    put("lastSyncAt", lastSyncAt ?: JSONObject.NULL)
                    put("pendingCount", eventDao.pendingCount())
                    put("deadLetterCount", eventDao.deadLetterCount())
                }
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("getSyncStatus failed: ${e.message}", e)
            }
        }
    }

    override fun handleOnDestroy() {
        scope.cancel()
        super.handleOnDestroy()
    }
}
