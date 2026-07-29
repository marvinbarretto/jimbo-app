package dev.marvinbarretto.jimbo

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocalShoutClient — POSTs harvested Instagram posts to LocalShout's ingest API.
 *
 * WHY NOT REUSE [JimboClient]
 * That client disables TLS verification wholesale, because the Jimbo API runs
 * behind a self-signed certificate on a personal VPS. LocalShout is on Vercel
 * with a real certificate, so it gets normal validation. Reusing the trust-all
 * client "because it already exists" would throw away certificate checking on a
 * connection that never needed it weakened — the kind of quiet erosion that is
 * hard to spot later.
 *
 * CONCEPT MAPPING (JS)
 * `HttpURLConnection` is the verbose ancestor of `fetch`. Open, set headers,
 * write the body to an output stream, read the status and response. There is no
 * promise: the call blocks, which is why every use sits inside a coroutine on
 * the IO dispatcher.
 */
object LocalShoutClient {

    private const val TAG = "JimboScreen"
    private const val TIMEOUT_MS = 20_000

    /** True when the build actually has somewhere to send to. */
    fun isConfigured(): Boolean =
        BuildConfig.LOCALSHOUT_INGEST_URL.isNotBlank() && BuildConfig.LOCALSHOUT_SCREEN_KEY.isNotBlank()

    /**
     * POST a batch of screen records.
     *
     * @return HTTP status paired with the response body, or -1 and the error
     *   message when the request could not be made at all. Callers must treat
     *   anything other than 2xx as "keep the rows queued" — deleting on a
     *   failure would silently lose captures.
     */
    fun postScreenBatch(jsonBody: String): Pair<Int, String> {
        val endpoint = "${BuildConfig.LOCALSHOUT_INGEST_URL}/api/ingest/screen"
        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.LOCALSHOUT_SCREEN_KEY}")
            }

            OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()

            Log.d(TAG, "POST $endpoint → $status")
            status to body
        } catch (e: Exception) {
            // Offline, DNS failure, timeout — all normal on a phone that moves.
            Log.w(TAG, "POST $endpoint failed: ${e.message}")
            -1 to (e.message ?: "request failed")
        }
    }
}
