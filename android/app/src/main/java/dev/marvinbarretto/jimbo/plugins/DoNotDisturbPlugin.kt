package dev.marvinbarretto.jimbo.plugins

import android.content.Intent
import android.provider.Settings
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.DoNotDisturbController

/**
 * Lets the hosted shell turn Do Not Disturb on for a focus block and give it
 * back. State lives in [DoNotDisturbController], not here, so the restore alarm
 * works without a plugin instance.
 */
@CapacitorPlugin(name = "DoNotDisturb")
class DoNotDisturbPlugin : Plugin() {

    @PluginMethod
    fun getState(call: PluginCall) {
        call.resolve(JSObject().apply {
            put("hasAccess", DoNotDisturbController.hasAccess(context))
            put("engaged", DoNotDisturbController.isEngaged(context))
        })
    }

    @PluginMethod
    fun requestAccess(call: PluginCall) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        call.resolve()
    }

    @PluginMethod
    fun enable(call: PluginCall) {
        if (!DoNotDisturbController.hasAccess(context)) {
            call.reject("access_not_granted")
            return
        }
        DoNotDisturbController.enable(context, call.getDouble("restoreAtMillis")?.toLong())
        call.resolve()
    }

    @PluginMethod
    fun restore(call: PluginCall) {
        DoNotDisturbController.restore(context)
        call.resolve()
    }
}
