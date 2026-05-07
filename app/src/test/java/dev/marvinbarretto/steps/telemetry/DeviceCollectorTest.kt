package dev.marvinbarretto.steps.telemetry

import android.net.wifi.WifiManager
import android.os.BatteryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCollectorTest {

    @Test
    fun chargerTypeNameMapsExpectedValues() {
        assertEquals("ac", chargerTypeName(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals("usb", chargerTypeName(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals("wireless", chargerTypeName(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertNull(chargerTypeName(-1))
    }

    @Test
    fun networkKindReturnsNoneWhenCapabilitiesMissing() {
        assertEquals("none", networkKind(null))
    }

    @Test
    fun ssidHashReturnsStableTruncatedHash() {
        val hash = ssidHash("\"My WiFi\"")

        assertEquals(16, hash?.length)
        assertEquals(hash, ssidHash("My WiFi"))
        assertTrue(hash!!.matches(Regex("[0-9a-f]{16}")))
        assertNull(ssidHash(WifiManager.UNKNOWN_SSID))
        assertNull(ssidHash("<unknown ssid>"))
    }
}
