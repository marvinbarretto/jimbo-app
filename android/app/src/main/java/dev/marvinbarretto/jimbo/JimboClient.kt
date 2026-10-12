package dev.marvinbarretto.jimbo

import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HTTP client that POSTs telemetry data to the Jimbo API.
 *
 * TLS is checked the normal way. Until 2026-10-13 this accepted any
 * certificate and any hostname, left over from a self-signed VPS cert; the API
 * now sits behind a real certificate (jimbo.fourfoldmedia.uk), and
 * TlsVerificationTest keeps verification switched on.
 */
object JimboClient : dev.marvinbarretto.jimbo.nudge.FoodLogPoster {


    fun postTelemetryEvents(jsonBody: String): Pair<Int, String> =
        postJson("/api/telemetry/events", jsonBody)

    override fun postFoodLog(jsonBody: String): Pair<Int, String> =
        postJson("/api/coach/food-log", jsonBody)

    /** [query] is a raw query string, e.g. `days=2&limit=1`. */
    fun getFoodLog(query: String): Pair<Int, String> =
        sendJson("GET", "/api/coach/food-log?$query", null)

    fun postGymSession(jsonBody: String): Pair<Int, String> =
        postJson("/api/gym/sessions", jsonBody)

    fun postGymCardio(sessionId: String, jsonBody: String): Pair<Int, String> =
        postJson("/api/gym/sessions/$sessionId/cardio", jsonBody)

    fun patchGymSession(sessionId: String, jsonBody: String): Pair<Int, String> =
        sendJson("PATCH", "/api/gym/sessions/$sessionId", jsonBody)

    fun getJson(path: String): Pair<Int, String> = sendJson("GET", path, null)

    /**
     * BuildConfig.JIMBO_API_URL comes from local.properties and JIMBO_API_KEY
     * from the environment at build time: `scripts/install-phone.sh` runs the
     * build under `op run`, so the key's only home is 1Password (Fleet/phone).
     */
    private fun postJson(path: String, jsonBody: String): Pair<Int, String> =
        sendJson("POST", path, jsonBody)

    private fun sendJson(method: String, path: String, jsonBody: String?): Pair<Int, String> {
        val endpoint = "${BuildConfig.JIMBO_API_URL}$path"
        android.util.Log.d("JimboSync", "$method $endpoint (${jsonBody?.length ?: 0} bytes)")
        val url = URL(endpoint)

        val conn = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.JIMBO_API_KEY)
            doOutput = jsonBody != null
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        if (jsonBody != null) OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        android.util.Log.d("JimboSync", "$method response: $code — $body")
        return Pair(code, body)
    }
}
