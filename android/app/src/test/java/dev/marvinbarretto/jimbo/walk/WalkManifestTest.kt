package dev.marvinbarretto.jimbo.walk

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The walk service as the manifest declares it; cwd is android/app, as HealthPermissionsManifestTest relies on. */
class WalkManifestTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    private fun declaresPermission(name: String) =
        Regex("""<uses-permission\s+android:name="$name"""").containsMatchIn(manifest)

    @Test fun `walk service is a location foreground service, not exported`() {
        val service = Regex("""<service\b[^>]*android:name="\.walk\.WalkSessionService"[^>]*>""")
            .find(manifest)?.value
        assertTrue("WalkSessionService is not declared", service != null)
        assertTrue(service!!, service.contains("""android:foregroundServiceType="location""""))
        assertTrue(service, service.contains("""android:exported="false""""))
    }

    @Test fun `declares the permissions a location foreground service needs`() {
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
        ).forEach { assertTrue("$it not declared", declaresPermission(it)) }
    }
}
