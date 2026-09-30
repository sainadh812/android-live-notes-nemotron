package com.sainadh.livenotes.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Versioned, bounded-memory AEAD stream. Each 64 KiB frame has its own GCM tag,
 * nonce and sequence-number AAD. An authenticated empty final frame is mandatory.
 * A single GCM CipherInputStream can buffer an entire multi-GB backup on Android.
 */
internal object BackupCipher {
    private val magic = "LNOTES01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 600_000
    private const val CHUNK_BYTES = 64 * 1024
    private const val HEADER_BYTES = 8 + 4 + 4 + 16 + 8 + 4
    private const val TAG_BYTES = 16

    fun encrypt(output: OutputStream, password: CharArray): OutputStream {
        require(password.size in 8..1024) { "Use a backup password of 8 to 1,024 characters." }
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(8).also(random::nextBytes)
        val header = ByteArrayOutputStream(HEADER_BYTES).also { bytes ->
            DataOutputStream(bytes).apply {
                write(magic); writeInt(1); writeInt(ITERATIONS); write(salt); write(nonce); writeInt(CHUNK_BYTES)
            }
        }.toByteArray()
        val key = derive(password, salt, ITERATIONS)
        return EncryptingStream(output, header, nonce, key)
    }

    fun decrypt(input: InputStream, password: CharArray): InputStream {
        require(password.size in 8..1024) { "Enter the password used to create this backup." }
        val header = ByteArray(HEADER_BYTES)
        try { DataInputStream(input).readFully(header) }
        catch (error: IOException) { throw IOException("This backup is incomplete or is not a Live Meeting Notes backup.", error) }
        val data = ByteBuffer.wrap(header)
        val actualMagic = ByteArray(8).also(data::get)
        if (!actualMagic.contentEquals(magic) || data.int != 1) throw IOException("Unsupported backup format.")
        val iterations = data.int
        // The KDF cost is untrusted until authentication. Never accept an attacker-selected huge cost.
        if (iterations != ITERATIONS) throw IOException("Unsupported backup password format.")
        val salt = ByteArray(16).also(data::get)
        val nonce = ByteArray(8).also(data::get)
        if (data.int != CHUNK_BYTES) throw IOException("Unsupported backup frame size.")
        return DecryptingStream(input, header, nonce, derive(password, salt, iterations))
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, nonce: ByteArray, sequence: Int, size: Int): Cipher {
        val iv = ByteBuffer.allocate(12).put(nonce).putInt(sequence).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(128, iv))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(8).putInt(sequence).putInt(size).array())
        }
    }

    private class EncryptingStream(
        output: OutputStream, private val header: ByteArray, private val nonce: ByteArray, private val key: SecretKeySpec
    ) : OutputStream() {
        private val output = DataOutputStream(output)
        private val buffer = ByteArray(CHUNK_BYTES)
        private var count = 0
        private var sequence = 0
        private var closed = false
        init { this.output.write(header) }
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed) { "Backup stream is closed" }
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            var at = offset
            var remaining = length
            while (remaining > 0) {
                val next = minOf(remaining, buffer.size - count)
                bytes.copyInto(buffer, count, at, at + next)
                count += next; at += next; remaining -= next
                if (count == buffer.size) writeFrame(count)
            }
        }
        private fun writeFrame(size: Int) {
            if (sequence == Int.MAX_VALUE) throw IOException("Backup exceeds the supported size.")
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key, header, nonce, sequence++, size).doFinal(buffer, 0, size)
            output.writeInt(size); output.write(encrypted)
            count = 0
        }
        override fun flush() = output.flush()
        override fun close() {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            try { if (count > 0) writeFrame(count); writeFrame(0); output.flush() }
            catch (error: Throwable) { failure = error; throw error }
            finally {
                buffer.fill(0)
                try { output.close() } catch (error: Throwable) { if (failure != null) failure.addSuppressed(error) else throw error }
            }
        }
    }

    private class DecryptingStream(
        input: InputStream, private val header: ByteArray, private val nonce: ByteArray, private val key: SecretKeySpec
    ) : InputStream() {
        private val input = DataInputStream(input)
        private var buffer = ByteArray(0)
        private var offset = 0
        private var sequence = 0
        private var complete = false
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, start: Int, length: Int): Int {
            require(start >= 0 && length >= 0 && start <= bytes.size - length)
            if (length == 0) return 0
            if (offset == buffer.size && !nextFrame()) return -1
            val count = minOf(length, buffer.size - offset)
            buffer.copyInto(bytes, start, offset, offset + count); offset += count
            return count
        }
        private fun nextFrame(): Boolean {
            if (complete) return false
            try {
                if (sequence == Int.MAX_VALUE) throw IOException("Backup exceeds the supported size.")
                val size = input.readInt()
                if (size !in 0..CHUNK_BYTES) throw IOException("Invalid backup frame.")
                val encrypted = ByteArray(size + TAG_BYTES).also(input::readFully)
                val clear = cipher(Cipher.DECRYPT_MODE, key, header, nonce, sequence++, size).doFinal(encrypted)
                buffer.fill(0); buffer = clear; offset = 0
                if (size == 0) {
                    if (input.read() != -1) throw IOException("Unexpected data after the backup footer.")
                    complete = true
                    return false
                }
                return true
            } catch (error: GeneralSecurityException) {
                throw IOException("The backup password is incorrect or the backup is damaged.", error)
            } catch (error: java.io.EOFException) {
                throw IOException("The backup is incomplete or damaged.", error)
            }
        }
        override fun close() { buffer.fill(0); input.close() }
    }
}
