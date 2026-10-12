package dev.marvinbarretto.jimbo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must check the server's certificate. Until 2026-10-13 JimboClient
 * accepted any certificate and any hostname (a leftover from a self-signed VPS
 * cert), so anyone on the same Wi-Fi could pose as Jimbo and read the API key
 * and the telemetry. The API now sits behind a real certificate at
 * jimbo.fourfoldmedia.uk, so nothing in the app may switch verification off.
 */
class TlsVerificationTest {

    private val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun noSourceInstallsATrustAllTrustManager() {
        val offenders = sources.filter { "X509TrustManager" in it.readText() }.map { it.name }
        assertTrue("trust managers found in $offenders", offenders.isEmpty())
    }

    @Test
    fun noSourceAcceptsEveryHostname() {
        val pattern = Regex("""HostnameVerifier\s*\{\s*_\s*,\s*_\s*->\s*true\s*}""")
        val offenders = sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("hostname verification switched off in $offenders", offenders.isEmpty())
    }

    @Test
    fun theApiKeyIsNotReadFromLocalProperties() {
        val gradle = File("build.gradle").readText()
        assertTrue(
            "JIMBO_API_KEY must come from the environment (op run), not local.properties",
            !Regex("""JIMBO_API_KEY".*localProps""").containsMatchIn(gradle),
        )
    }
}
