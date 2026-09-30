package com.sainadh.livenotes.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class BackupCipherTest {
    private val password = "correct horse backup battery".toCharArray()

    @Test fun roundTripSpansFramesAndDoesNotExposePlaintext() {
        val source = ByteArray(200_013) { (it % 251).toByte() }
        "private-api-key-and-meeting-summary".toByteArray().copyInto(source)
        val encoded = encrypt(source)
        assertArrayEquals(source, decrypt(encoded))
        assertFalse(encoded.toString(Charsets.ISO_8859_1).contains("private-api-key-and-meeting-summary"))
        assertFalse(encoded.contentEquals(encrypt(source)))
    }

    @Test fun wrongPasswordAndChangedAuthenticatedBytesFail() {
        val encoded = encrypt(ByteArray(70_000) { 42 })
        assertThrows(IOException::class.java) {
            BackupCipher.decrypt(ByteArrayInputStream(encoded), "different password".toCharArray()).use { it.readBytes() }
        }
        val corrupt = encoded.copyOf().also { it[70] = (it[70].toInt() xor 1).toByte() }
        assertThrows(IOException::class.java) { decrypt(corrupt) }
    }

    @Test fun missingFinalFrameTruncationTrailingGarbageAndReorderedFramesFail() {
        val encoded = encrypt(ByteArray(2 * 65_536) { (it % 113).toByte() })
        // Header: 44 bytes. Each full frame: 4 length + 65,536 ciphertext + 16 tag.
        val header = 44
        val frame = 65_556
        val swapped = encoded.copyOf()
        encoded.copyInto(swapped, header, header + frame, header + frame * 2)
        encoded.copyInto(swapped, header + frame, header, header + frame)
        listOf(encoded.copyOf(encoded.size - 20), encoded.copyOf(encoded.size - 1), encoded + byteArrayOf(0), swapped).forEach {
            assertThrows(IOException::class.java) { decrypt(it) }
        }
    }

    @Test fun untrustedHeaderCostAndUnboundedFrameLengthsAreRejected() {
        val encoded = encrypt("hello".toByteArray())
        val expensive = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(12, Int.MAX_VALUE) }
        assertThrows(IOException::class.java) { decrypt(expensive) }
        val oversized = encoded.copyOf().also { ByteBuffer.wrap(it).putInt(44, Int.MAX_VALUE) }
        assertThrows(IOException::class.java) { decrypt(oversized) }
        assertThrows(IllegalArgumentException::class.java) { BackupCipher.encrypt(ByteArrayOutputStream(), "short".toCharArray()) }
    }

    @Test fun authenticatedEmptyPayloadRequiresItsFooter() {
        val encoded = encrypt(ByteArray(0))
        assertArrayEquals(ByteArray(0), decrypt(encoded))
        assertThrows(IOException::class.java) { decrypt(encoded.copyOf(44)) }
    }

    private fun encrypt(source: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        BackupCipher.encrypt(output, password).use { it.write(source) }
    }.toByteArray()

    private fun decrypt(source: ByteArray): ByteArray =
        BackupCipher.decrypt(ByteArrayInputStream(source), password).use { it.readBytes() }
}
