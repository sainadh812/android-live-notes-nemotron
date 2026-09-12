package com.sainadh.livenotes.desktop.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class Pcm16MonoConverterTest {
    private fun bytes(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = sample.toByte()
            bytes[index * 2 + 1] = (sample.toInt() shr 8).toByte()
        }
    }

    @Test fun nativeRatePreservesAllSignedSamples() {
        val pcm = shortArrayOf(Short.MIN_VALUE, -1000, 0, 1000, Short.MAX_VALUE)
        assertArrayEquals(pcm, Pcm16MonoConverter(16_000, 1).convert(bytes(pcm), pcm.size * 2))
    }

    @Test fun stereoDownsamplingIsIndependentOfReadBoundaries() {
        val pcm = ShortArray(48_000 * 2) { index -> (sin(index / 2 * 0.02) * 10_000).toInt().toShort() }
        val input = bytes(pcm)
        val whole = Pcm16MonoConverter(48_000, 2).convert(input, input.size)
        val converter = Pcm16MonoConverter(48_000, 2)
        val chunks = mutableListOf<Short>()
        for (start in input.indices step 788) {
            val part = input.copyOfRange(start, minOf(input.size, start + 788))
            chunks.addAll(converter.convert(part, part.size).toList())
        }
        assertEquals(16_000, whole.size)
        assertArrayEquals(whole, chunks.toShortArray())
    }

    @Test fun arbitraryRateAndStereoMixRetainDurationAndBoundedAmplitude() {
        val input = bytes(ShortArray(44_100 * 2) { if (it % 2 == 0) 12_000 else -4_000 })
        val output = Pcm16MonoConverter(44_100, 2).convert(input, input.size)
        assertTrue(output.size in 15_999..16_000)
        assertTrue(output.all { it == 4_000.toShort() })
    }
}
