package dev.marvinbarretto.jimbo.plugins

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.ActivityStateStore
import org.json.JSONObject

@CapacitorPlugin(name = "ActivityContext")
class ActivityContextPlugin : Plugin() {

    @PluginMethod
    fun getCurrentActivity(call: PluginCall) {
        val state = ActivityStateStore.getCurrent(context)
        val result = JSObject().apply {
            put("state", state.state)
            put("since", state.since ?: JSONObject.NULL)
        }
        call.resolve(result)
    }
}
