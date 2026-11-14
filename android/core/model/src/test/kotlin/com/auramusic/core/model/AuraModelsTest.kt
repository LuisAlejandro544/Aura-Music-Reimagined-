package com.auramusic.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `equalizer state normalizes 10 bands and clamps out of range gains`() {
        val raw = EqualizerState(
            isEnabled = true,
            bandGainsDb = listOf(20f, -30f, Float.NaN, 3.5f),
            activePresetId = EqualizerState.PRESET_CUSTOM_ID,
        )
        val normalized = raw.normalized()
        assertEquals(EqualizerState.BAND_COUNT, normalized.bandGainsDb.size)
        assertEquals(EqualizerState.MAX_GAIN_DB, normalized.bandGainsDb[0], 0.001f)
        assertEquals(EqualizerState.MIN_GAIN_DB, normalized.bandGainsDb[1], 0.001f)
        assertEquals(0f, normalized.bandGainsDb[2], 0.001f)
        assertEquals(3.5f, normalized.bandGainsDb[3], 0.001f)
        assertEquals(0f, normalized.bandGainsDb[9], 0.001f)
    }

    @Test
    fun `download state clamps normalizedProgress and tracks busy lifecycle`() {
        val idle = DownloadState()
        assertFalse(idle.isBusy)
        assertEquals(0f, idle.normalizedProgress, 0.001f)

        val downloading = DownloadState(
            status = DownloadStatus.DOWNLOADING,
            progressPercent = 64.5f,
        )
        assertTrue(downloading.isBusy)
        assertEquals(0.645f, downloading.normalizedProgress, 0.001f)

        val invalid = DownloadState(
            status = DownloadStatus.ERROR,
            progressPercent = Float.NaN,
        )
        assertFalse(invalid.isBusy)
        assertEquals(0f, invalid.normalizedProgress, 0.001f)

        val over = DownloadState(
            status = DownloadStatus.COMPLETED,
            progressPercent = 140f,
        )
        assertFalse(over.isBusy)
        assertEquals(1f, over.normalizedProgress, 0.001f)
    }
}
