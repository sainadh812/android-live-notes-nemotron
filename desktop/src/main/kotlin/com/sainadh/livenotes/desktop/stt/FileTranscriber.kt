package com.sainadh.livenotes.desktop.stt

import com.sainadh.livenotes.desktop.audio.PcmWavReader
import com.sainadh.livenotes.stt.NativeTranscriptSegments
import com.sainadh.livenotes.stt.NativeWordTimingFile
import com.sainadh.livenotes.stt.TranscriptSampleClock
import com.sainadh.livenotes.stt.TranscriptUpdate
import java.io.File

data class FileTranscription(val text: String, val durationMs: Long, val wordTiming: String?)

/** Synchronous worker/CLI entrypoint; bounded PCM blocks, no microphone required. */
object FileTranscriber {
    fun transcribe(
        model: File,
        audio: File,
        language: String = "en-US",
        onUpdate: (TranscriptUpdate) -> Unit = {}
    ): FileTranscription {
        NativeSpeech.ensureLoaded()
        val segments = NativeTranscriptSegments()
        val clock = TranscriptSampleClock(16_000)
        PcmWavReader(audio).use { wav ->
            require(wav.sampleRate == 16_000) { "File transcription requires a 16 kHz mono PCM16 WAV" }
            val handle = NativeSpeech.nativeInit(model.canonicalPath, language, -1)
            check(handle != 0L) { "Could not initialize speech model" }
            try {
                var position = 0L
                val bytes = ByteArray(16_000)
                while (position < wav.frameCount) {
                    val frames = wav.render(position.toDouble(), 1.0, bytes, 8_000).frames
                    check(frames > 0) { "Audio ended before its declared length" }
                    val pcm = FloatArray(frames) { index ->
                        ((bytes[index * 2].toInt() and 255) or (bytes[index * 2 + 1].toInt() shl 8)).toShort() / 32768f
                    }
                    val delta = NativeSpeech.nativeFeedPcm(handle, pcm)
                    clock.consume(frames)
                    delta?.let { segments.update(it).forEach { update -> onUpdate(clock.stamp(update)) } }
                    check(!NativeSpeech.nativeWasTruncated(handle)) { "Speech model output limit reached; transcription is incomplete" }
                    position += frames
                }
                val text = NativeSpeech.nativeFinalizeStream(handle)
                onUpdate(clock.stamp(segments.finish(text)))
                check(!NativeSpeech.nativeWasTruncated(handle)) { "Speech model output limit reached; transcription is incomplete" }
                val timing = NativeSpeech.nativeWordTimings(handle).takeIf(String::isNotBlank)
                timing?.let { NativeWordTimingFile.parse(it) }
                return FileTranscription(text, wav.durationMs, timing)
            } finally {
                NativeSpeech.nativeDestroy(handle)
            }
        }
    }
}

/** Run with -Dlivenotes.native.dir=... and arguments model.gguf recording.wav. */
object SpeechFileCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3) { "Usage: SpeechFileCli model.gguf recording.wav [language] or --fixtures directory" }
        // Resolve the Unicode fixture filename inside Java: older Windows Java
        // launchers can lose non-ANSI characters passed through command-line args.
        val (model, wav) = if (args[0] == "--fixtures") {
            val directory = File(args[1])
            val models = directory.listFiles { file -> file.extension == "gguf" }.orEmpty()
            require(models.size == 1) { "Expected exactly one GGUF speech fixture" }
            models.single() to File(directory, "jfk.wav")
        } else File(args[0]) to File(args[1])
        val result = FileTranscriber.transcribe(model, wav, args.getOrElse(2) { "en-US" })
        println("duration_ms=${result.durationMs}")
        println("timed_words=${result.wordTiming?.let { NativeWordTimingFile.parse(it).size } ?: 0}")
        println(result.text)
        check(result.text.isNotBlank()) { "The fixture produced no transcript" }
    }
}
