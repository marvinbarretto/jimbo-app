package dev.marvinbarretto.jimbo.plugins

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import dev.marvinbarretto.jimbo.BuildConfig

/**
 * Hands the hosted web shell the API credentials from BuildConfig, so it can
 * call jimbo-api with X-API-Key instead of depending on a WebView session
 * cookie that expires.
 *
 * Resolves synchronously — these are compile-time constants, so unlike
 * TelemetryPlugin there's no IO to move off the main thread.
 */
@CapacitorPlugin(name = "Auth")
class AuthPlugin : Plugin() {

    @PluginMethod
    fun getApiCredentials(call: PluginCall) {
        val result = JSObject().apply {
            put("apiKey", BuildConfig.JIMBO_API_KEY)
            put("apiUrl", BuildConfig.JIMBO_API_URL)
            put("deviceId", BuildConfig.JIMBO_DEVICE_ID)
        }
        call.resolve(result)
    }
}
