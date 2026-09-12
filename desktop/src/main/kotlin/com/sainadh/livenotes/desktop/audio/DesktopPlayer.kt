package com.sainadh.livenotes.desktop.audio

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.floor
import kotlin.math.roundToInt

data class PlaybackState(
    val file: File? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val error: String? = null,
)

/** Streams WAV data on one daemon worker. Callbacks run on that worker, not the UI thread.
 * Speed uses linear resampling, so changing speed also changes pitch.
 */
class DesktopPlayer(private val onState: (PlaybackState) -> Unit) : AutoCloseable {
    private data class Request(
        val file: File? = null, val playing: Boolean = false,
        val seek: Long? = null, val speed: Float = 1f, val revision: Long = 0,
    )
    private val lock = Object()
    private var request = Request()
    private var pendingUnload: CompletableFuture<Unit>? = null
    private val workerReleased = CompletableFuture<Unit>()
    @Volatile private var closed = false
    @Volatile private var activeLine: SourceDataLine? = null
    private val worker = Thread(::runWorker, "meeting-audio-playback").apply {
        isDaemon = true
        start()
    }

    fun load(file: File) = update { copy(file = file.absoluteFile, playing = false, seek = 0) }
    fun play() = update { copy(playing = true) }
    fun pause() = update(ignoreIfUnloading = true) { copy(playing = false) }
    fun seekTo(positionMs: Long) = update { copy(seek = positionMs.coerceAtLeast(0)) }
    fun setSpeed(speed: Float) {
        require(speed.isFinite() && speed in 0.5f..2f) { "Speed must be between 0.5 and 2" }
        update { copy(speed = speed) }
    }

    /**
     * Releases the WAV file before deletion on Windows. Call off the UI thread.
     * Returns only after worker-owned handles close; failure prevents deletion.
     * The player remains reusable. The owner must exclude new playback while it
     * deletes the file after this method returns.
     */
    fun unload() {
        check(Thread.currentThread() !== worker) { "Do not unload from a playback callback" }
        val released = synchronized(lock) {
            if (closed) workerReleased else pendingUnload ?: CompletableFuture<Unit>().also { completion ->
                pendingUnload = completion
                request = request.copy(file = null, playing = false, seek = 0, revision = request.revision + 1)
                lock.notifyAll()
            }
        }
        try {
            try {
                released.get(3, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // A faulty output driver may block despite available() checks.
                // Its close must not turn this bounded wait into an unbounded one.
                Thread({ runCatching { activeLine?.close() } }, "playback-release").apply { isDaemon = true; start() }
                released.get(2, TimeUnit.SECONDS)
            }
        } catch (error: TimeoutException) {
            throw IllegalStateException("The audio device did not release this recording. Try again after playback stops.", error)
        } catch (error: ExecutionException) {
            throw IllegalStateException("Could not release this recording for deletion.", error.cause)
        }
    }

    private fun update(ignoreIfUnloading: Boolean = false, change: Request.() -> Request) = synchronized(lock) {
        if (!closed) {
            if (pendingUnload != null && ignoreIfUnloading) return@synchronized
            check(pendingUnload == null) { "Wait until the recording has finished unloading" }
            request = request.change().copy(revision = request.revision + 1)
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        // Closing the line also unblocks a device driver stuck in write/open.
        runCatching { activeLine?.close() }
    }

    private fun runWorker() {
        var reader: PcmWavReader? = null
        var line: SourceDataLine? = null
        var current = Request(revision = -1)
        var position = 0.0
        var epochSample = 0.0
        var epochFrame = 0L
        var queuedFrames = 0L
        var playing = false
        var nextPublish = 0L
        val bytes = ByteArray(2048)

        fun audiblePosition(): Double = if (playing && line != null) {
            (epochSample + (line!!.longFramePosition - epochFrame).coerceAtLeast(0) * current.speed)
                .coerceAtMost(reader?.frameCount?.toDouble() ?: 0.0)
        } else position

        fun publish(error: String? = null) {
            val wav = reader
            val state = PlaybackState(
                file = current.file,
                positionMs = if (wav != null) (audiblePosition() * 1000 / wav.sampleRate).toLong() else 0,
                durationMs = wav?.durationMs ?: 0,
                isPlaying = playing,
                speed = current.speed,
                error = error,
            )
            // Serialize delivery with request changes so stale loads cannot overwrite new state.
            synchronized(lock) {
                if (!closed && current.revision == request.revision) runCatching { onState(state) }
            }
        }

        fun completeUnload() = synchronized(lock) {
            if (current.file == null && reader == null && line == null) {
                pendingUnload?.complete(Unit)
                pendingUnload = null
            }
        }

        try {
            while (!closed) {
                val latest = synchronized(lock) { request }
                try {
                    if (latest.revision != current.revision) {
                        position = audiblePosition()
                        playing = false
                        line?.stop()
                        line?.flush()
                        val changedFile = latest.file != current.file || reader == null
                        current = latest
                        if (changedFile) {
                            reader?.close()
                            reader = null
                            line?.close()
                            line = null
                            activeLine = null
                            position = 0.0
                            reader = latest.file?.let(::PcmWavReader)
                        }
                        val wav = reader
                        latest.seek?.let { position = if (wav == null) 0.0 else
                            (it.toDouble() * wav.sampleRate / 1000).coerceIn(0.0, wav.frameCount.toDouble()) }
                        // Consume a seek exactly once, including commands arriving while opening a device.
                        synchronized(lock) {
                            if (request.revision == latest.revision) request = request.copy(seek = null)
                        }
                        if (latest.playing && wav != null && wav.frameCount > 0) {
                            if (position >= wav.frameCount) position = 0.0
                            if (line == null) {
                                val format = AudioFormat(wav.sampleRate.toFloat(), 16, 1, true, false)
                                line = AudioSystem.getSourceDataLine(format)
                                activeLine = line
                                line!!.open(format, (wav.sampleRate / 10).coerceAtLeast(1024) * 2)
                            }
                            val isCurrent = synchronized(lock) { !closed && request.revision == current.revision }
                            if (!isCurrent) continue
                            epochSample = position
                            epochFrame = line!!.longFramePosition
                            queuedFrames = 0
                            line!!.start()
                            playing = true
                        }
                        completeUnload()
                        publish()
                    }

                    val wav = reader
                    val output = line
                    if (playing && wav != null && output != null) {
                        val count = minOf(bytes.size / 2, output.available() / 2)
                        if (position < wav.frameCount && count > 0) {
                            val rendered = wav.render(position, current.speed.toDouble(), bytes, count)
                            // available() limits writes to the small currently free output buffer.
                            val written = output.write(bytes, 0, rendered.frames * 2) / 2
                            position = (position + written * current.speed.toDouble()).coerceAtMost(wav.frameCount.toDouble())
                            queuedFrames += written
                        }
                        if (position >= wav.frameCount && output.longFramePosition - epochFrame >= queuedFrames) {
                            playing = false
                            position = wav.frameCount.toDouble()
                            output.stop()
                            synchronized(lock) {
                                if (request.revision == current.revision) request = request.copy(playing = false)
                            }
                            publish()
                        } else if (System.nanoTime() >= nextPublish) {
                            publish()
                            nextPublish = System.nanoTime() + 80_000_000
                        }
                    }
                } catch (failure: Exception) {
                    playing = false
                    runCatching { line?.close() }
                    line = null
                    activeLine = null
                    synchronized(lock) {
                        if (request.revision == current.revision) request = request.copy(playing = false, seek = null)
                        if (latest.file == null) {
                            pendingUnload?.completeExceptionally(failure)
                            pendingUnload = null
                        }
                    }
                    publish(failure.message ?: "Audio playback failed")
                }
                synchronized(lock) {
                    if (!closed && request.revision == current.revision) lock.wait(if (playing) 10L else 1000L)
                }
            }
        } finally {
            val lineFailure = runCatching { line?.close() }.exceptionOrNull()
            val readerFailure = runCatching { reader?.close() }.exceptionOrNull()
            activeLine = null
            synchronized(lock) {
                val failure = readerFailure ?: lineFailure
                if (failure == null) {
                    pendingUnload?.complete(Unit)
                    workerReleased.complete(Unit)
                } else {
                    pendingUnload?.completeExceptionally(failure)
                    workerReleased.completeExceptionally(failure)
                }
                pendingUnload = null
            }
            runCatching { onState(PlaybackState(file = current.file, speed = current.speed)) }
        }
    }
}

/** Small random-access cache; memory use is independent of meeting duration. */
internal class PcmWavReader(file: File) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    val sampleRate: Int
    val frameCount: Long
    val durationMs: Long get() = frameCount * 1000 / sampleRate
    private val dataStart: Long
    private val cache = ByteArray(8192)
    private var cacheStart = -1L
    private var cacheFrames = 0

    init {
        try {
            require(input.length() >= 44 && ascii() == "RIFF") { "Not a WAV recording" }
            val riffEnd = uint() + 8
            require(riffEnd == input.length() && ascii() == "WAVE") { "Incomplete WAV recording" }
            var rate = 0
            var start = -1L
            var size = 0L
            var formatFound = false
            while (input.filePointer + 8 <= riffEnd) {
                val id = ascii()
                val length = uint()
                val payload = input.filePointer
                require(length <= riffEnd - payload) { "Truncated WAV chunk" }
                if (id == "fmt ") {
                    require(!formatFound && length >= 16) { "Invalid WAV format" }
                    val encoding = ushort()
                    val channels = ushort()
                    val sampleRate = uint()
                    val byteRate = uint()
                    val alignment = ushort()
                    val bits = ushort()
                    require(encoding == 1 && channels == 1 && bits == 16 && alignment == 2 &&
                        sampleRate in 1..192_000 && byteRate == sampleRate * 2) { "Playback requires mono PCM16 WAV audio" }
                    rate = sampleRate.toInt()
                    formatFound = true
                } else if (id == "data") {
                    require(start < 0 && length % 2 == 0L) { "Invalid WAV sample data" }
                    start = payload
                    size = length
                }
                val next = payload + length + (length and 1)
                require(next <= riffEnd) { "Truncated WAV padding" }
                input.seek(next)
            }
            require(formatFound && start >= 0 && input.filePointer == riffEnd) { "Missing WAV format or samples" }
            sampleRate = rate
            dataStart = start
            frameCount = size / 2
        } catch (failure: Throwable) {
            input.close()
            throw failure
        }
    }

    data class Rendered(val frames: Int, val nextPosition: Double)

    fun render(position: Double, speed: Double, output: ByteArray, maxFrames: Int = output.size / 2): Rendered {
        require(position.isFinite() && position >= 0 && speed.isFinite() && speed in 0.5..2.0)
        require(maxFrames in 0..output.size / 2)
        var cursor = position
        var frames = 0
        while (frames < maxFrames && cursor < frameCount) {
            val index = floor(cursor).toLong()
            val a = sample(index)
            val b = sample((index + 1).coerceAtMost(frameCount - 1))
            val value = (a + (b - a) * (cursor - index)).roundToInt().coerceIn(-32768, 32767)
            output[frames * 2] = value.toByte()
            output[frames * 2 + 1] = (value shr 8).toByte()
            frames++
            cursor += speed
        }
        return Rendered(frames, cursor.coerceAtMost(frameCount.toDouble()))
    }

    private fun sample(frame: Long): Int {
        if (frame < cacheStart || frame >= cacheStart + cacheFrames) {
            cacheStart = frame
            cacheFrames = minOf(cache.size / 2L, frameCount - frame).toInt()
            input.seek(dataStart + frame * 2)
            input.readFully(cache, 0, cacheFrames * 2)
        }
        val offset = ((frame - cacheStart) * 2).toInt()
        return ((cache[offset].toInt() and 255) or (cache[offset + 1].toInt() shl 8)).toShort().toInt()
    }

    private fun ascii(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
    private fun uint(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffff_ffffL
    private fun ushort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
    override fun close() = input.close()
}
