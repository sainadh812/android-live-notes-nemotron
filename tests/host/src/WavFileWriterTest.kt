import com.sainadh.livenotes.audio.WavFileWriter
import com.sainadh.livenotes.stt.TranscriptSampleClock
import com.sainadh.livenotes.stt.TranscriptStatus
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

fun main() {
    val directory = Files.createTempDirectory("wav-writer-test").toFile()
    try {
        val destination = File(directory, "recording.wav")
        val writer = WavFileWriter(destination, 16_000)
        writer.write(shortArrayOf(99, Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE, 99), 1, 5)
        check(!destination.exists()) { "Unfinished file published" }
        val saved = writer.finish()
        check(saved == destination && writer.finish() == destination)
        val bytes = destination.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 8) == "WAVEfmt ")
        check(header.getInt(4) == bytes.size - 8 && header.getInt(16) == 16)
        check(header.getShort(20).toInt() == 1 && header.getShort(22).toInt() == 1)
        check(header.getInt(24) == 16_000 && header.getInt(28) == 32_000)
        check(header.getShort(32).toInt() == 2 && header.getShort(34).toInt() == 16)
        check(String(bytes, 36, 4) == "data" && header.getInt(40) == 10)
        check((0 until 5).map { header.getShort(44 + it * 2) } == listOf<Short>(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE))
        check(!File(directory, "recording.wav.part").exists())
        check(runCatching { writer.write(shortArrayOf(1), 0, 1) }.isFailure)
        check(runCatching { WavFileWriter(destination, 16_000) }.isFailure)
        check(destination.readBytes().contentEquals(bytes)) { "Existing recording overwritten" }

        val empty = File(directory, "empty.wav")
        check(WavFileWriter(empty, 16_000).finish() == null)
        check(!empty.exists() && !File(directory, "empty.wav.part").exists())

        val durable = File(directory, "durable.wav")
        val checkpointed = WavFileWriter(durable, 16_000)
        checkpointed.write(ShortArray(32_000) { 16384 }, 0, 32_000)
        check(checkpointed.durationMs == 2_000L)
        val checkpoint = File(directory, "durable.wav.part").readBytes()
        check(ByteBuffer.wrap(checkpoint).order(ByteOrder.LITTLE_ENDIAN).getInt(40) == 64_000)
        check(!durable.exists())
        checkpointed.write(ShortArray(1_123), 0, 1_123)
        checkpointed.finish()
        check(durable.length() == 44L + 33_123L * 2)
        check(checkpointed.durationMs == 2_070L)

        check(WavFileWriter.recover(durable)?.durationMs == 2_070L)
        val interrupted = File(directory, "interrupted.wav")
        val unfinished = File(directory, "interrupted.wav.part")
        // Simulate death after the last PCM write but before the header checkpoint.
        val staleHeader = durable.readBytes().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 36).putInt(40, 0)
        }
        unfinished.writeBytes(staleHeader + byteArrayOf(123))
        val recovered = checkNotNull(WavFileWriter.recover(interrupted))
        check(recovered.file == interrupted && recovered.durationMs == 2_070L)
        check(interrupted.readBytes().contentEquals(durable.readBytes()))
        check(!unfinished.exists())
        check(WavFileWriter.recover(File(directory, "missing.wav")) == null)
        val corrupt = File(directory, "corrupt.wav.part")
        corrupt.writeBytes(ByteArray(100))
        check(runCatching { WavFileWriter.recover(File(directory, "corrupt.wav")) }.isFailure)
        check(corrupt.isFile && !File(directory, "corrupt.wav").exists())

        val clock = TranscriptSampleClock(16_000)
        clock.consume(8_000)
        val partial = clock.stamp(TranscriptUpdate(0, "first", TranscriptStatus.PARTIAL))
        check(partial.startMs == 0L && partial.endMs == 500L)
        clock.consume(8_000)
        val committed = clock.stamp(TranscriptUpdate(0, "first words", TranscriptStatus.FINAL))
        check(committed.startMs == 0L && committed.endMs == 1_000L)
        val next = clock.stamp(TranscriptUpdate(1, "next", TranscriptStatus.PARTIAL))
        check(next.startMs == 1_000L && next.endMs == 1_000L)
        clock.consume(1_123)
        val tail = clock.stamp(TranscriptUpdate(1, "next words", TranscriptStatus.FINAL))
        check(tail.startMs == 1_000L && tail.endMs == 1_070L)
        println("PASS PCM WAV integrity, checkpoints, interrupted recovery, finalization, and sample timestamps")
    } finally {
        directory.deleteRecursively()
    }
}
