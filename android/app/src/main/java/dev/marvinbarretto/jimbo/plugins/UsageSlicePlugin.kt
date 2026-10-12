package dev.marvinbarretto.jimbo.plugins

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.telemetry.UsageEventRow
import dev.marvinbarretto.jimbo.telemetry.hasUsageAccess
import dev.marvinbarretto.jimbo.telemetry.summarizeBlockUsage
import java.time.Instant

/**
 * Foreground apps for an arbitrary window — the focus retro's "what pulled you
 * away" facts. Reads the Usage Access grant the collector already holds.
 */
@CapacitorPlugin(name = "UsageSlice")
class UsageSlicePlugin : Plugin() {

    @PluginMethod
    fun getWindowUsage(call: PluginCall) {
        val from = call.getDouble("fromMillis")?.toLong()
        val to = call.getDouble("toMillis")?.toLong()
        if (from == null || to == null || to < from) {
            call.reject("fromMillis and toMillis are required, from <= to")
            return
        }
        val manager = context.getSystemService(UsageStatsManager::class.java)
        if (manager == null || !hasUsageAccess(context)) {
            call.resolve(JSObject().apply {
                put("granted", false)
                put("apps", JSArray())
            })
            return
        }

        val events = mutableListOf<UsageEventRow>()
        val raw = manager.queryEvents(from, to)
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            events += UsageEventRow(event.packageName, event.eventType, Instant.ofEpochMilli(event.timeStamp))
        }

        val apps = JSArray()
        summarizeBlockUsage(events, ignoredPackages()).forEach {
            apps.put(JSObject().apply {
                put("pkg", it.pkg)
                put("label", label(it.pkg))
                put("launches", it.launches)
                put("foregroundSeconds", it.foregroundSeconds)
            })
        }
        call.resolve(JSObject().apply {
            put("granted", true)
            put("apps", apps)
        })
    }

    /** This shell, System UI and whatever the home screen is: being on them is not a distraction. */
    private fun ignoredPackages(): Set<String> {
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
        )?.activityInfo?.packageName
        return setOfNotNull(context.packageName, "com.android.systemui", home)
    }

    private fun label(pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
