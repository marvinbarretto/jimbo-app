package dev.marvinbarretto.jimbo.plugins

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.place.PlaceStateStore

/**
 * Named place (home / gym / other) from the geofences — never coordinates, so
 * it is safe to hand to the hosted shell.
 */
@CapacitorPlugin(name = "LocationContext")
class LocationContextPlugin : Plugin() {

    @PluginMethod
    fun getCurrentPlace(call: PluginCall) {
        val (place, since) = PlaceStateStore.current(context)
        call.resolve(JSObject().apply {
            put("place", place.wire)
            put("since", since)
        })
    }
}
