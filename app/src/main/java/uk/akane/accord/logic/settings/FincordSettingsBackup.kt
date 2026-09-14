package uk.akane.accord.logic.settings

import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Arrays
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Compact, typed `.fnc` settings backup. Credentials and transient playback state are not keys. */
object FincordSettingsBackup {
    const val MIME_TYPE = "application/vnd.fincord.settings"
    const val FILE_EXTENSION = ".fnc"

    private const val ENVELOPE_MAGIC = 0x464E4531 // FNE1: Fincord encrypted envelope v1
    private const val PAYLOAD_MAGIC = 0x464E4331 // FNC1
    private const val VERSION = 1
    private const val CHECKSUM_BYTES = Long.SIZE_BYTES
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024
    private const val MAX_ENTRIES = 4_096
    private const val KDF_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val AAD = "Fincord settings backup v1".toByteArray(Charsets.UTF_8)

    private const val BOOLEAN = 1
    private const val INT = 2
    private const val LONG = 3
    private const val FLOAT = 4
    private const val STRING = 5
    private const val STRING_SET = 6

    /*
     * Explicitly allow portable choices. This is intentionally not "every default preference":
     * that file also contains signed-in flags, active remote-session ids, queue state and migration
     * markers. A future credential can therefore never leak merely because it was added to prefs.
     */
    val portableKeys: Set<String> = setOf(
        "automatic_lyrics_translation",
        "backgroundless_status_bar",
        "immersive_mode",
        "quality_badge_content",
        "output_codec_badge",
        "quality_badge_flash",
        "rotate_now_playing",
        "settings_ui_mesh_gradient",
        "theme_mode",
        "floatoutput",
        "ps_hardware_acc",
        "skip_silence",
        "usb_hifi",
        "sync_on_startup",
        "autoplay",
        "autoplay_similar",
        "finnect_enabled",
        "trim_lyrics",
        "cache_size_limit",
        "download_quality",
        "metered_streaming_quality",
        "streaming_quality",
        "lastfm_scrobbling_enabled",
        "lastfm_server_scrobbles",
        "automix_transitions",
        "artistBlacklist",
        "songBlacklist",
    )

    fun fromPreferences(preferences: SharedPreferences, password: CharArray): ByteArray =
        encode(preferences.all.filterKeys(portableKeys::contains), password)

    fun restoreToPreferences(
        preferences: SharedPreferences,
        bytes: ByteArray,
        password: CharArray,
    ): Int {
        val decoded = decode(bytes, password).filterKeys(portableKeys::contains)
        val editor = preferences.edit()
        decoded.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit()) { "Android could not persist the restored settings" }
        return decoded.size
    }

    fun encode(values: Map<String, *>, password: CharArray): ByteArray {
        require(password.size >= 8) { "Password must be at least 8 characters" }
        val payload = encodePayload(values)
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val key = deriveKey(password, salt)
        val encrypted = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(AAD)
                doFinal(payload)
            }
        } finally {
            Arrays.fill(key, 0)
            Arrays.fill(payload, 0)
        }
        return ByteArrayOutputStream(encrypted.size + 48).also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(ENVELOPE_MAGIC)
                output.writeShort(VERSION)
                output.writeInt(KDF_ITERATIONS)
                output.writeByte(salt.size)
                output.write(salt)
                output.writeByte(nonce.size)
                output.write(nonce)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
        }.toByteArray()
    }

    private fun encodePayload(values: Map<String, *>): ByteArray {
        val entries = values.entries
            .filter { it.key in portableKeys && isSupported(it.value) }
            .sortedBy { it.key }
        require(entries.size <= MAX_ENTRIES) { "Too many settings" }

        val payload = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(PAYLOAD_MAGIC)
                output.writeShort(VERSION)
                output.writeInt(entries.size)
                entries.forEach { (key, value) ->
                    output.writeUTF(key)
                    when (value) {
                        is Boolean -> { output.writeByte(BOOLEAN); output.writeBoolean(value) }
                        is Int -> { output.writeByte(INT); output.writeInt(value) }
                        is Long -> { output.writeByte(LONG); output.writeLong(value) }
                        is Float -> { output.writeByte(FLOAT); output.writeFloat(value) }
                        is String -> { output.writeByte(STRING); output.writeUTF(value) }
                        is Set<*> -> {
                            output.writeByte(STRING_SET)
                            val strings = value.filterIsInstance<String>().sorted()
                            output.writeInt(strings.size)
                            strings.forEach(output::writeUTF)
                        }
                    }
                }
            }
        }.toByteArray()
        val checksum = CRC32().apply { update(payload) }.value
        return ByteArrayOutputStream(payload.size + CHECKSUM_BYTES).also { buffer ->
            DataOutputStream(buffer).use { output ->
                output.write(payload)
                output.writeLong(checksum)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray, password: CharArray): Map<String, Any> {
        require(password.size >= 8) { "Password must be at least 8 characters" }
        require(bytes.size in 32..MAX_FILE_BYTES) { "Invalid backup size" }
        val encrypted: ByteArray
        val salt: ByteArray
        val nonce: ByteArray
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == ENVELOPE_MAGIC) { "Not an encrypted Fincord backup" }
            require(input.readUnsignedShort() == VERSION) { "Unsupported backup version" }
            require(input.readInt() == KDF_ITERATIONS) { "Unsupported key derivation" }
            val saltSize = input.readUnsignedByte()
            require(saltSize == SALT_BYTES) { "Invalid salt" }
            salt = ByteArray(saltSize).also(input::readFully)
            val nonceSize = input.readUnsignedByte()
            require(nonceSize == NONCE_BYTES) { "Invalid nonce" }
            nonce = ByteArray(nonceSize).also(input::readFully)
            val encryptedSize = input.readInt()
            require(encryptedSize in 16..input.available()) { "Invalid encrypted payload" }
            encrypted = ByteArray(encryptedSize).also(input::readFully)
            require(input.available() == 0) { "Unexpected data after backup" }
        }
        val key = deriveKey(password, salt)
        val payload = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
                updateAAD(AAD)
                doFinal(encrypted)
            }
        } finally {
            Arrays.fill(key, 0)
        }
        return try {
            decodePayload(payload)
        } finally {
            Arrays.fill(payload, 0)
        }
    }

    private fun decodePayload(bytes: ByteArray): Map<String, Any> {
        require(bytes.size in (CHECKSUM_BYTES + 10)..MAX_FILE_BYTES) { "Invalid backup size" }
        val payloadSize = bytes.size - CHECKSUM_BYTES
        val expectedChecksum = DataInputStream(
            ByteArrayInputStream(bytes, payloadSize, CHECKSUM_BYTES),
        ).use(DataInputStream::readLong)
        val actualChecksum = CRC32().apply { update(bytes, 0, payloadSize) }.value
        require(expectedChecksum == actualChecksum) { "Backup checksum does not match" }

        return DataInputStream(ByteArrayInputStream(bytes, 0, payloadSize)).use { input ->
            require(input.readInt() == PAYLOAD_MAGIC) { "Not a Fincord settings backup" }
            require(input.readUnsignedShort() == VERSION) { "Unsupported backup version" }
            val count = input.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid settings count" }
            buildMap {
                repeat(count) {
                    val key = input.readUTF()
                    require(key !in this) { "Duplicate setting: $key" }
                    val value: Any = when (input.readUnsignedByte()) {
                        BOOLEAN -> input.readBoolean()
                        INT -> input.readInt()
                        LONG -> input.readLong()
                        FLOAT -> input.readFloat()
                        STRING -> input.readUTF()
                        STRING_SET -> {
                            val size = input.readInt()
                            require(size in 0..MAX_ENTRIES) { "Invalid set size" }
                            buildSet { repeat(size) { add(input.readUTF()) } }
                        }
                        else -> throw IllegalArgumentException("Unknown setting type")
                    }
                    put(key, value)
                }
                require(input.available() == 0) { "Unexpected data in backup" }
            }
        }
    }

    private fun isSupported(value: Any?): Boolean =
        value is Boolean || value is Int || value is Long || value is Float ||
            value is String || value is Set<*>

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
