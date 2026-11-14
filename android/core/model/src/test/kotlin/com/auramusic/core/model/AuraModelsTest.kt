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

    @Test
    fun `artist playlist builder groups same artist case-insensitively`() {
        val t1 = Track(1L, "uri://1", "Song B", "Daft Punk", "Discovery", 180_000L, "/proc.png", true, false, "fp1", 10L)
        val t2 = Track(2L, "uri://2", "Song A", "  daft   punk ", "Homework", 200_000L, "/real.jpg", false, false, "fp2", 20L)
        val t3 = Track(3L, "uri://3", "Solo", "Nujabes", "Modal Soul", 210_000L, null, true, false, "fp3", 30L)

        val grouped = ArtistPlaylistBuilder.buildFromTracks(listOf(t1, t2, t3))
        assertEquals(2, grouped.size)
        val daft = grouped.first()
        assertEquals("daft punk", daft.artistKey)
        assertEquals(2, daft.trackCount)
        assertEquals(380_000L, daft.totalDurationMs)
        assertEquals("/real.jpg", daft.coverFilePath)
        assertEquals(listOf("Song A", "Song B"), daft.tracks.map { it.title })
    }

    @Test
    fun `playback queue helper handles playNext, move, remove, and clearUpcoming`() {
        val tracks = (1L..4L).map { id ->
            Track(id, "uri://$id", "Track $id", "Artist", "Album", 120_000L, null, true, false, "fp$id")
        }
        val extra = Track(5L, "uri://5", "Track 5", "Artist", "Album", 120_000L, null, true, false, "fp5")

        val afterNext = PlaybackQueueHelper.insertPlayNext(tracks, currentIndex = 1, track = extra)
        assertEquals(listOf(1L, 2L, 5L, 3L, 4L), afterNext.queue.map { it.id })
        assertEquals(1, afterNext.currentIndex)
        assertEquals(2, afterNext.insertedAt)

        val afterMove = PlaybackQueueHelper.moveItem(afterNext.queue, currentIndex = 1, fromIndex = 3, toIndex = 0)
        assertEquals(listOf(3L, 1L, 2L, 5L, 4L), afterMove.queue.map { it.id })
        assertEquals(2, afterMove.currentIndex)

        val afterRemove = PlaybackQueueHelper.removeItem(afterMove.queue, currentIndex = 2, removeIndex = 0)
        assertEquals(listOf(1L, 2L, 5L, 4L), afterRemove.queue.map { it.id })
        assertEquals(1, afterRemove.currentIndex)

        val afterClear = PlaybackQueueHelper.clearUpcoming(afterRemove.queue, currentIndex = 1)
        assertEquals(listOf(1L, 2L), afterClear.queue.map { it.id })
        assertEquals(1, afterClear.currentIndex)
    }

    @Test
    fun `playback tuning helper clamps speed and crossfade seconds accurately`() {
        assertEquals(1, PlaybackTuningHelper.clampCrossfadeSeconds(-3))
        assertEquals(12, PlaybackTuningHelper.clampCrossfadeSeconds(30))
        assertEquals(6, PlaybackTuningHelper.clampCrossfadeSeconds(6))

        assertEquals(0.5f, PlaybackTuningHelper.clampPlaybackSpeed(0.1f), 0.001f)
        assertEquals(2.0f, PlaybackTuningHelper.clampPlaybackSpeed(4.0f), 0.001f)
        assertEquals(1.0f, PlaybackTuningHelper.clampPlaybackSpeed(Float.NaN), 0.001f)
        assertEquals(1.25f, PlaybackTuningHelper.clampPlaybackSpeed(1.254f), 0.001f)
        assertEquals("1.0x", PlaybackTuningHelper.formatSpeedLabel(1.0f))
        assertEquals("1.25x", PlaybackTuningHelper.formatSpeedLabel(1.25f))
    }

    @Test
    fun `equal power crossfade curve preserves smooth gain transition and triggers at exact window`() {
        val (startOut, startIn) = PlaybackTuningHelper.computeEqualPowerGains(0f)
        assertEquals(1f, startOut, 0.001f)
        assertEquals(0f, startIn, 0.001f)

        val (midOut, midIn) = PlaybackTuningHelper.computeEqualPowerGains(0.5f)
        assertEquals(midOut, midIn, 0.001f)
        assertTrue(midOut in 0.65f..0.75f)

        val (endOut, endIn) = PlaybackTuningHelper.computeEqualPowerGains(1f)
        assertEquals(0f, endOut, 0.001f)
        assertEquals(1f, endIn, 0.001f)

        // Con 5s antes del fin a velocidad 1.0x debe dispararse cuando faltan <= 5000ms
        assertFalse(
            PlaybackTuningHelper.shouldTriggerCrossfade(
                crossfadeEnabled = true,
                isPlaying = true,
                isAlreadyCrossfading = false,
                hasNextMediaItem = true,
                repeatOneEnabled = false,
                durationMs = 120_000L,
                positionMs = 114_000L,
                playbackSpeed = 1.0f,
                crossfadeSeconds = 5,
            ),
        )
        assertTrue(
            PlaybackTuningHelper.shouldTriggerCrossfade(
                crossfadeEnabled = true,
                isPlaying = true,
                isAlreadyCrossfading = false,
                hasNextMediaItem = true,
                repeatOneEnabled = false,
                durationMs = 120_000L,
                positionMs = 115_200L,
                playbackSpeed = 1.0f,
                crossfadeSeconds = 5,
            ),
        )
        // A velocidad 2.0x, 6000ms de pista restantes equivalen a 3000ms reales -> dispara con crossfade=5s
        assertTrue(
            PlaybackTuningHelper.shouldTriggerCrossfade(
                crossfadeEnabled = true,
                isPlaying = true,
                isAlreadyCrossfading = false,
                hasNextMediaItem = true,
                repeatOneEnabled = false,
                durationMs = 120_000L,
                positionMs = 114_000L,
                playbackSpeed = 2.0f,
                crossfadeSeconds = 5,
            ),
        )
    }
}


