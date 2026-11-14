package com.auramusic.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas unitarias de reglas puras del dominio de Aura Music.
 */
class AuraModelsTest {

    @Test
    fun `progressFraction clamps cleanly between 0 and 1`() {
        val track = Track(
            id = 1L,
            contentUri = "content://audio/1",
            title = "Aura",
            artist = "Artist",
            album = "Album",
            durationMs = 100_000L,
            coverFilePath = null,
            isProceduralCover = true,
            trackFingerprint = "abc",
        )
        val half = PlaybackState(currentTrack = track, positionMs = 50_000L, durationMs = 100_000L)
        assertEquals(0.5f, half.progressFraction, 0.001f)

        val zeroDuration = PlaybackState(currentTrack = track, positionMs = 10_000L, durationMs = 0L)
        assertEquals(0f, zeroDuration.progressFraction, 0.001f)
    }

    @Test
    fun `accent color lookup falls back to AMETHYST for unknown name`() {
        assertEquals(AppAccentColor.EMERALD, AppAccentColor.fromName("EMERALD"))
        assertEquals(AppAccentColor.AMETHYST, AppAccentColor.fromName("UNKNOWN"))
    }
}
