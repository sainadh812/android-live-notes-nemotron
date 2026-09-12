package com.sainadh.livenotes.desktop.audio

import org.junit.Assert.*
import org.junit.Test

class AudioDevicesTest {
    @Test fun outputNegotiationFinds48000StereoAndClosesRejectedLines() {
        val attempts = mutableListOf<FakeOutputLine>()
        val output = AudioDevices.openCompatibleOutput(16_000) { info ->
            val format = info.formats.single()
            FakeOutputLine(format) { it.sampleRate == 48_000f && it.channels == 2 }
                .also(attempts::add).line
        }
        try {
            assertEquals(48_000f, output.format.sampleRate, 0f)
            assertEquals(2, output.format.channels)
            assertTrue(output.isOpen)
            assertTrue(attempts.size > 1)
            assertTrue(attempts.dropLast(1).all { it.closed.count == 0L })
        } finally { output.close() }
    }

    @Test fun failedNegotiationClosesEveryCandidate() {
        val attempts = mutableListOf<FakeOutputLine>()
        val error = runCatching {
            AudioDevices.openCompatibleOutput(16_000) { info ->
                FakeOutputLine(info.formats.single()) { false }.also(attempts::add).line
            }
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("playback device"))
        assertTrue(attempts.isNotEmpty() && attempts.all { it.closed.count == 0L })
    }
}
