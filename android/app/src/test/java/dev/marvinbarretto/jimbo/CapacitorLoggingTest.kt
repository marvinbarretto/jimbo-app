package dev.marvinbarretto.jimbo

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capacitor logs every native plugin result to logcat in debug builds, and the
 * Auth plugin's result is the API key. On 2026-10-13 that put the phone key in
 * a log read over adb, and the key had to be rotated. Logging stays off.
 */
class CapacitorLoggingTest {

    @Test
    fun capacitorPluginLoggingIsOff() {
        val config = File("../../capacitor.config.ts").readText()
        assertTrue(
            "capacitor.config.ts must set loggingBehavior: 'none'",
            Regex("""loggingBehavior:\s*'none'""").containsMatchIn(config),
        )
    }
}
