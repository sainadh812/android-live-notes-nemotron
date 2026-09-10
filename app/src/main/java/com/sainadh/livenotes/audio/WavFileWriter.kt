package com.sainadh.livenotes.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A single-owner, bounded-memory writer for 16-bit mono PCM. Checkpoints keep the
 * private .part file playable after an interrupted process. Only finish publishes
 * the final filename, after the header and samples have been synced to storage.
 */
internal class WavFileWriter(private val destination: File, private val sampleRateHz: Int) {
    data class RecoveredAudio(val file: File, val durationMs: Long)

    companion object {
        /**
         * Call only for a recording known to have no live capture owner (for
         * example, at process startup). Recovers our fixed PCM format without
         * loading the recording into memory or trusting an unfinished data size.
         * A completed file also covers process death between rename and callback.
         */
        fun recover(destination: File): RecoveredAudio? {
            val completed = destination.isFile
            val source = if (completed) destination else File(destination.parentFile, "${destination.name}.part")
            if (!source.isFile) return null
            var durationMs: Long
            var dataBytes: Long
            RandomAccessFile(source, if (completed) "r" else "rw").use { input ->
                check(input.length() >= 44) { "Incomplete recording header" }
                val bytes = ByteArray(44)
                input.readFully(bytes)
                val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val sampleRate = header.getInt(24)
                check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 8, Charsets.US_ASCII) == "WAVEfmt " &&
                    String(bytes, 36, 4, Charsets.US_ASCII) == "data" &&
                    header.getInt(16) == 16 && header.getShort(20).toInt() == 1 &&
                    header.getShort(22).toInt() == 1 && sampleRate in 1..192_000 &&
                    header.getInt(28) == sampleRate * 2 && header.getShort(32).toInt() == 2 &&
                    header.getShort(34).toInt() == 16) { "Unsupported recording format" }
                dataBytes = ((input.length() - 44) / 2) * 2 // Drop an incomplete final PCM sample.
                check(dataBytes <= 0xffff_ffffL - 36) { "Recording exceeds WAV size limit" }
                durationMs = dataBytes / 2 * 1_000L / sampleRate
                if (completed) {
                    check(input.length() == dataBytes + 44 &&
                        (header.getInt(40).toLong() and 0xffff_ffffL) == dataBytes &&
                        (header.getInt(4).toLong() and 0xffff_ffffL) == dataBytes + 36) {
                        "Finalized recording header does not match audio"
                    }
                } else {
                    input.setLength(44 + dataBytes)
                    header.putInt(4, (36 + dataBytes).toInt())
                    header.putInt(40, dataBytes.toInt())
                    input.seek(0)
                    input.write(bytes)
                    input.fd.sync()
                }
            }
            if (dataBytes == 0L) {
                if (!completed) check(source.delete()) { "Could not remove empty interrupted recording" }
                return null
            }
            if (!completed) {
                check(!destination.exists() && source.renameTo(destination)) { "Could not recover recording file" }
            }
            return RecoveredAudio(destination, durationMs)
        }
    }

    private val partial = File(destination.parentFile, "${destination.name}.part")
    private val output: RandomAccessFile
    private var closed = false
    private var samples = 0L
    private var checkpointSamples = 0L
    val durationMs: Long get() = samples * 1_000L / sampleRateHz

    init {
        require(sampleRateHz in 1..192_000) { "Invalid audio sample rate" }
        destination.parentFile?.let { parent ->
            check(parent.isDirectory || parent.mkdirs()) { "Could not create recording folder" }
        }
        check(!destination.exists()) { "Recording file already exists" }
        check(partial.createNewFile()) { "An unfinished recording already exists" }
        output = RandomAccessFile(partial, "rw")
        try {
            writeHeader()
        } catch (error: Throwable) {
            runCatching { output.close() }
            throw error
        }
    }

    fun write(pcm: ShortArray, offset: Int, count: Int) {
        check(!closed) { "Recording is already closed" }
        require(offset >= 0 && count >= 0 && offset <= pcm.size - count)
        // RIFF/WAV uses unsigned 32-bit chunk sizes. Stop before wrapping a header.
        check(samples + count <= (0xffff_ffffL - 36L) / 2L) { "Recording reached the WAV size limit" }
        if (count == 0) return
        val bytes = ByteArray(count * 2)
        repeat(count) { index ->
            val value = pcm[offset + index].toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
        output.seek(44L + samples * 2L)
        output.write(bytes)
        samples += count
        if (samples - checkpointSamples >= sampleRateHz * 2L) {
            writeHeader()
            output.fd.sync()
            checkpointSamples = samples
        }
    }

    /** Returns null for a recording with no samples; never exposes an empty WAV. */
    fun finish(): File? {
        if (closed) return destination.takeIf { it.isFile && samples > 0 }
        closed = true
        try {
            output.setLength(44L + samples * 2L)
            writeHeader()
            output.fd.sync()
        } finally {
            output.close()
        }
        if (samples == 0L) {
            check(partial.delete()) { "Could not remove empty recording" }
            return null
        }
        check(!destination.exists() && partial.renameTo(destination)) { "Could not finalize recording file" }
        return destination
    }

    private fun writeHeader() {
        output.seek(0)
        output.writeBytes("RIFF")
        writeInt(36L + samples * 2L)
        output.writeBytes("WAVEfmt ")
        writeInt(16)
        writeShort(1) // Linear PCM.
        writeShort(1) // Mono.
        writeInt(sampleRateHz.toLong())
        writeInt(sampleRateHz * 2L)
        writeShort(2)
        writeShort(16)
        output.writeBytes("data")
        writeInt(samples * 2L)
    }

    private fun writeShort(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeInt(value: Long) {
        repeat(4) { output.write(((value ushr (it * 8)) and 0xff).toInt()) }
    }
}
