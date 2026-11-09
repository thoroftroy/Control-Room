package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsSearchTest {
    @Test fun settingsSearchIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(settingsMatch("Bluetooth and devices", " bluetooth "))
        assertTrue(settingsMatch("Date and time", "TIME"))
        assertTrue(settingsMatch("Wi-Fi", "  "))
        assertFalse(settingsMatch("Wi-Fi", "battery"))
    }
}
