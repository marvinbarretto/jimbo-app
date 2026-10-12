package dev.marvinbarretto.jimbo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthPermissionsManifestTest {

    @Test
    fun readerPermissionsMatchManifestHealthConnectDeclarations() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""android:name="(android\.permission\.health\.[A-Z_]+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(declared, HealthConnectReader.PERMISSIONS)
    }
}
