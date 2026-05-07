package dev.marvinbarretto.steps

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
 * HTTP client that POSTs fitness data to the Jimbo API.
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

    /**
     * Returns (httpStatusCode, responseBody) so the caller can check success.
     */
    fun postSync(jsonBody: String): Pair<Int, String> =
        postJson("/api/fitness/sync", jsonBody)

    fun postTelemetryEvents(jsonBody: String): Pair<Int, String> =
        postJson("/api/telemetry/events", jsonBody)

    /**
     * BuildConfig.JIMBO_API_URL and JIMBO_API_KEY are injected at build time
     * from local.properties via build.gradle.kts — similar to .env vars in JS.
     */
    private fun postJson(path: String, jsonBody: String): Pair<Int, String> {
        val endpoint = "${BuildConfig.JIMBO_API_URL}$path"
        android.util.Log.d("StepsSync", "POST $endpoint (${jsonBody.length} bytes)")
        val url = URL(endpoint)

        // HttpsURLConnection is Android's built-in HTTP client.
        // .apply {} is Kotlin's way of configuring an object inline
        // (like Object.assign() in JS but type-safe).
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            // Override the default SSL socket factory to trust all certs
            sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, trustAllManager, SecureRandom())
            }.socketFactory
            // Skip hostname verification (the cert doesn't match the IP)
            hostnameVerifier = HostnameVerifier { _, _ -> true }
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", BuildConfig.JIMBO_API_KEY)
            doOutput = true  // tells the connection we're sending a body
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        // Write the JSON body to the request (like fetch body in JS)
        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

        // Read the response — errorStream is used for non-2xx status codes
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            .bufferedReader().readText()
        android.util.Log.d("StepsSync", "POST response: $code — $body")
        return Pair(code, body)
    }
}
