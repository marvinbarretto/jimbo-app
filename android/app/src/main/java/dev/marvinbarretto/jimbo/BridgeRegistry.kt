package dev.marvinbarretto.jimbo

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.getcapacitor.Bridge
import com.getcapacitor.WebViewListener
import org.json.JSONObject

/**
 * Tracks which native capabilities the cap shell exposes, and injects
 * `window.__JIMBO_BRIDGE__` into the WebView on every page load so the
 * PWA can feature-detect and degrade gracefully in a desktop browser.
 *
 * Mirrors the localshout-next pattern: capabilities are name → version,
 * incremented when a plugin adds or breaks methods. Consumers do
 * `window.__JIMBO_BRIDGE__?.capabilities?.telemetry >= 1` rather than
 * sniffing `Capacitor.isNativePlatform()`, which lets us evolve plugins
 * without bumping a single global version.
 *
 * Re-injection on every onPageStarted is required because Android's
 * evaluateJavascript runs in the current document context only — new
 * navigations destroy the old context.
 */
class BridgeRegistry private constructor(context: Context) {

    private val capabilities = mutableMapOf<String, Int>()
    private val shellVersion: String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (e: Exception) {
        "0.0.0"
    }

    fun registerCapability(name: String, version: Int) {
        val existing = capabilities[name]
        if (existing == null || version >= existing) {
            capabilities[name] = version
            Log.d(TAG, "registerCapability(\"$name\", v$version)")
        }
    }

    fun attachToBridge(bridge: Bridge) {
        val script = generateInjectionScript()

        bridge.addWebViewListener(object : WebViewListener() {
            override fun onPageStarted(webView: WebView) {
                webView.evaluateJavascript(script, null)
            }
        })

        bridge.webView?.evaluateJavascript(script, null)
        Log.i(TAG, "attachToBridge — ${capabilities.size} capabilities, listener registered")
    }

    private fun generateInjectionScript(): String {
        val capsJson = JSONObject(capabilities as Map<String, Any>).toString()
        return "window.__JIMBO_BRIDGE__ = {" +
            "capabilities: $capsJson," +
            "shellVersion: \"$shellVersion\"" +
            "};"
    }

    companion object {
        private const val TAG = "JimboBridge"

        @Volatile
        private var instance: BridgeRegistry? = null

        fun getInstance(context: Context): BridgeRegistry =
            instance ?: synchronized(this) {
                instance ?: BridgeRegistry(context.applicationContext).also { instance = it }
            }
    }
}
