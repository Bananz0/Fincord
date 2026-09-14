package uk.akane.accord.logic.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FincordSettingsBackupTest {
    @Test
    fun typedSettingsRoundTripEncrypted() {
        val values = mapOf(
            "theme_mode" to "2",
            "sync_on_startup" to true,
            "cache_size_limit" to 512,
            "artistBlacklist" to setOf("beta", "alpha"),
        )

        val password = "correct horse".toCharArray()
        val first = FincordSettingsBackup.encode(values, password)
        val second = FincordSettingsBackup.encode(values, password)

        assertNotEquals(first.toList(), second.toList())
        assertFalse(first.toString(Charsets.UTF_8).contains("theme_mode"))
        assertEquals(values, FincordSettingsBackup.decode(first, password))
    }

    @Test
    fun credentialsAndUnknownStateAreNeverWritten() {
        val decoded = FincordSettingsBackup.decode(
            FincordSettingsBackup.encode(
                mapOf(
                    "theme_mode" to "1",
                    "access_token" to "secret",
                    "finnect_active_session_id" to "device-state",
                ),
                "password".toCharArray(),
            ),
            "password".toCharArray(),
        )

        assertEquals("1", decoded["theme_mode"])
        assertFalse(decoded.containsKey("access_token"))
        assertFalse(decoded.containsKey("finnect_active_session_id"))
    }

    @Test
    fun wrongPasswordAndCorruptionAreRejected() {
        val bytes = FincordSettingsBackup.encode(
            mapOf("theme_mode" to "1"),
            "right-pass".toCharArray(),
        )

        assertThrows(Exception::class.java) {
            FincordSettingsBackup.decode(bytes, "wrong-pass".toCharArray())
        }
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            FincordSettingsBackup.decode(bytes, "right-pass".toCharArray())
        }
    }
}
