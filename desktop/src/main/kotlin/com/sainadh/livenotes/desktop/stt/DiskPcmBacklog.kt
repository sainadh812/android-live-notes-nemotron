package com.sainadh.livenotes.desktop.stt

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * One capture writer and one inference reader, with independent file positions.
 * Only completely written PCM is published. Memory stays fixed when inference
 * falls behind; the temporary file is owned until both workers have finished.
 */
internal class DiskPcmBacklog(directory: File) : AutoCloseable {
    private val file: File
    private val writer: RandomAccessFile
    private val reader: RandomAccessFile
    private val committedSamples = AtomicLong()
    private val writeBuffer = ByteArray(BLOCK_SAMPLES * 2)
    private val readBuffer = ByteArray(BLOCK_SAMPLES * 2)
    private val fullChunk = FloatArray(BLOCK_SAMPLES)
    private var readSamples = 0L

    init {
        check(directory.isDirectory || directory.mkdirs()) { "Could not create recording folder" }
        file = Files.createFile(directory.toPath().resolve(".speech-backlog-${UUID.randomUUID()}.pcm")).toFile()
        var openedWriter: RandomAccessFile? = null
        try {
            openedWriter = RandomAccessFile(file, "rw")
            reader = RandomAccessFile(file, "r")
            writer = openedWriter
        } catch (failure: Throwable) {
            runCatching { openedWriter?.close() }
            runCatching { Files.deleteIfExists(file.toPath()) }
            throw failure
        }
    }

    /** Capture thread only. No inference lock, queue capacity, or reader wait. */
    fun append(pcm: ShortArray) {
        var offset = 0
        while (offset < pcm.size) {
            val count = minOf(BLOCK_SAMPLES, pcm.size - offset)
            repeat(count) { index ->
                val sample = pcm[offset + index].toInt()
                writeBuffer[index * 2] = sample.toByte()
                writeBuffer[index * 2 + 1] = (sample shr 8).toByte()
            }
            writer.write(writeBuffer, 0, count * 2)
            committedSamples.addAndGet(count.toLong())
            offset += count
        }
    }

    /** Inference thread only. Returned storage may be reused by the next read. */
    fun next(captureFinished: Boolean): FloatArray? {
        val available = committedSamples.get() - readSamples
        if (available == 0L || (!captureFinished && available < BLOCK_SAMPLES)) return null
        val count = minOf(available, BLOCK_SAMPLES.toLong()).toInt()
        reader.readFully(readBuffer, 0, count * 2)
        val chunk = if (count == BLOCK_SAMPLES) fullChunk else FloatArray(count)
        repeat(count) { index ->
            chunk[index] = ((readBuffer[index * 2].toInt() and 255) or
                (readBuffer[index * 2 + 1].toInt() shl 8)).toShort() / 32768f
        }
        readSamples += count
        return chunk
    }

    /** Must run after the capture worker has stopped using the writer. */
    override fun close() {
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        cleanup { writer.close() }
        cleanup { reader.close() }
        cleanup { Files.deleteIfExists(file.toPath()) }
        failure?.let { throw it }
    }

    companion object {
        private const val BLOCK_SAMPLES = 8_000
        private val OWNED_NAME = Regex("\\.speech-backlog-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pcm")

        /** Startup only, after acquiring the application instance lock, before capture is allowed. */
        fun cleanupAbandoned(directory: File): Int {
            if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return 0
            var removed = 0
            Files.newDirectoryStream(directory.toPath()).use { paths ->
                paths.forEach { path ->
                    if (OWNED_NAME.matches(path.fileName.toString()) && Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                        if (Files.deleteIfExists(path)) removed++
                    }
                }
            }
            return removed
        }
    }
}
