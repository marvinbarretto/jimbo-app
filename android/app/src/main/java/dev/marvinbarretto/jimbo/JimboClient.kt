package dev.marvinbarretto.jimbo

import java.io.OutputStreamWriter
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * HTTP client that POSTs telemetry data to the Jimbo API.
 *
 * Uses a trust-all TLS configuration because Jimbo runs behind a
 * self-signed certificate on the VPS. This is acceptable for a
 * personal single-user app — never do this in production.
 */
object JimboClient {

    // Accept any TLS certificate without verification.
    // Normally Android rejects self-signed certs — this bypasses that.
    private val trustAllManager = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })

    fun postTelemetryEvents(jsonBody: String): Pair<Int, String> =
        postJson("/api/telemetry/events", jsonBody)

    fun postGymSession(jsonBody: String): Pair<Int, String> =
        postJson("/api/gym/sessions", jsonBody)

    fun postGymCardio(sessionId: String, jsonBody: String): Pair<Int, String> =
        postJson("/api/gym/sessions/$sessionId/cardio", jsonBody)

    fun patchGymSession(sessionId: String, jsonBody: String): Pair<Int, String> =
        sendJson("PATCH", "/api/gym/sessions/$sessionId", jsonBody)

    /**
     * BuildConfig.JIMBO_API_URL and JIMBO_API_KEY are injected at build time
     * from local.properties via build.gradle.kts — similar to .env vars in JS.
     */
    private fun postJson(path: String, jsonBody: String): Pair<Int, String> =
        sendJson("POST", path, jsonBody)

    private fun sendJson(method: String, path: String, jsonBody: String): Pair<Int, String> {
        val endpoint = "${BuildConfig.JIMBO_API_URL}$path"
        android.util.Log.d("JimboSync", "$method $endpoint (${jsonBody.length} bytes)")
        val url = URL(endpoint)

        val conn = (url.openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, trustAllManager, SecureRandom())
            }.socketFactory
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            requestMethod = method
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.JIMBO_API_KEY)
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        android.util.Log.d("JimboSync", "$method response: $code — $body")
        return Pair(code, body)
    }
}
