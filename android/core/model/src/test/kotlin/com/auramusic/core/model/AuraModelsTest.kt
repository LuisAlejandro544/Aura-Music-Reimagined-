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

    @Test
    fun `track metadata helper normalizes title and artist and rejects blank title`() {
        val normalized = TrackMetadataHelper.normalizeOrNull(
            rawTitle = "   Mi   Canción   Favorita  ",
            rawArtist = "  Danny   Ocean ",
        )
        assertEquals("Mi Canción Favorita", normalized?.title)
        assertEquals("Danny Ocean", normalized?.artist)

        val fallbackArtist = TrackMetadataHelper.normalizeOrNull(
            rawTitle = "Solo Título",
            rawArtist = "   ",
        )
        assertEquals("Solo Título", fallbackArtist?.title)
        assertEquals(TrackMetadataHelper.DEFAULT_ARTIST, fallbackArtist?.artist)

        assertEquals(null, TrackMetadataHelper.normalizeOrNull("   ", "Artista"))
    }

    @Test
    fun `download format helper selects quality presets and applies format choice`() {
        val options = listOf(
            DownloadAudioFormatOption(
                formatId = "251",
                extension = "webm",
                codec = "Opus",
                bitrateKbps = 160,
                estimatedSizeBytes = 4_200_000L,
                label = "WEBM (Opus) • 160 kbps",
            ),
            DownloadAudioFormatOption(
                formatId = "140",
                extension = "m4a",
                codec = "AAC",
                bitrateKbps = 128,
                estimatedSizeBytes = 3_400_000L,
                label = "M4A (AAC) • 128 kbps",
            ),
            DownloadAudioFormatOption(
                formatId = "249",
                extension = "webm",
                codec = "Opus",
                bitrateKbps = 52,
                estimatedSizeBytes = 1_400_000L,
                label = "WEBM (Opus) • 52 kbps",
            ),
        )

        assertEquals(AudioDownloadQuality.BALANCED, AudioDownloadQuality.fromKey("balanced"))
        assertEquals(AudioDownloadQuality.MAX, AudioDownloadQuality.fromKey("unknown"))

        assertEquals("251", DownloadFormatHelper.pickDefaultOption(options, AudioDownloadQuality.MAX)?.formatId)
        assertEquals("251", DownloadFormatHelper.pickDefaultOption(options, AudioDownloadQuality.BALANCED)?.formatId)
        assertEquals("249", DownloadFormatHelper.pickDefaultOption(options, AudioDownloadQuality.DATA_SAVER)?.formatId)

        val preview = DownloadPreview(
            sourceUrl = "https://youtube.com/watch?v=demo",
            title = "Demo",
            channelOrArtist = "Aura",
            estimatedSizeBytes = 4_200_000L,
            audioFormatLabel = "WEBM (Opus) • 160 kbps",
            availableFormats = options,
            selectedFormatId = "251",
        )
        val switched = DownloadFormatHelper.selectOption(preview, "140")
        assertEquals("140", switched.selectedFormatId)
        assertEquals("M4A (AAC) • 128 kbps", switched.audioFormatLabel)
        assertEquals(3_400_000L, switched.estimatedSizeBytes)
    }

    @Test
    fun `audio effects state normalizes bounds and resolves slowed varispeed parameters`() {
        val raw = AudioEffectsState(
            reverbEnabled = true,
            reverbRoomSize = 2.5f,
            reverbDamping = -0.3f,
            reverbWetMix = Float.NaN,
            reverbWidth = 1.5f,
            slowedEnabled = true,
            slowedSpeed = 0.40f,
            slowedPreservePitch = false,
            slowedWarmth = 1.8f,
            spatial8dEnabled = true,
            spatial8dSpeed = 2.2f,
            spatial8dDepth = -0.4f,
            activePresetId = "",
        ).normalized()

        assertEquals(1.0f, raw.reverbRoomSize, 0.001f)
        assertEquals(0.0f, raw.reverbDamping, 0.001f)
        assertEquals(AudioEffectsState.DEFAULT_WET_MIX, raw.reverbWetMix, 0.001f)
        assertEquals(1.0f, raw.reverbWidth, 0.001f)
        assertEquals(AudioEffectsState.MIN_SLOWED_SPEED, raw.slowedSpeed, 0.001f)
        assertEquals(1.0f, raw.slowedWarmth, 0.001f)
        assertEquals(1.0f, raw.spatial8dSpeed, 0.001f)
        assertEquals(0.0f, raw.spatial8dDepth, 0.001f)
        assertEquals(AudioEffectsState.PRESET_CUSTOM_ID, raw.activePresetId)

        val spatialPreset = AudioEffectsState.fromPreset(
            AudioEffectsState.findPresetById("spatial_8d_slowed")!!,
        )
        assertTrue(spatialPreset.spatial8dEnabled)
        assertTrue(spatialPreset.slowedEnabled)
        assertTrue(spatialPreset.reverbEnabled)
        assertEquals("8D • Slowed 0.85x • Reverb 34%", PlaybackTuningHelper.formatEffectsSummary(spatialPreset))

        val slowedReverb = AudioEffectsState.fromPreset(
            AudioEffectsState.findPresetById("slowed_reverb")!!,
        )
        val tuning = PlaybackTuning(playbackSpeed = 1.25f)
        val resolvedSlowed = PlaybackTuningHelper.resolvePlaybackParameters(tuning, slowedReverb)
        assertEquals(0.85f, resolvedSlowed.speed, 0.001f)
        assertEquals(0.85f, resolvedSlowed.pitch, 0.001f)

        val resolvedPreservePitch = PlaybackTuningHelper.resolvePlaybackParameters(
            tuning,
            slowedReverb.copy(slowedPreservePitch = true),
        )
        assertEquals(0.85f, resolvedPreservePitch.speed, 0.001f)
        assertEquals(1.0f, resolvedPreservePitch.pitch, 0.001f)

        val resolvedOff = PlaybackTuningHelper.resolvePlaybackParameters(
            tuning,
            AudioEffectsState(),
        )
        assertEquals(1.25f, resolvedOff.speed, 0.001f)
        assertEquals(1.0f, resolvedOff.pitch, 0.001f)
    }

    @Test
    fun `crossfeed preset normalizes bounds and formats summary cleanly`() {
        val crossfeedPreset = AudioEffectsState.fromPreset(
            AudioEffectsState.findPresetById("headphone_crossfeed")!!,
        )
        assertTrue(crossfeedPreset.crossfeedEnabled)
        assertTrue(crossfeedPreset.isAnyEffectEnabled)
        assertEquals(0.48f, crossfeedPreset.crossfeedStrength, 0.001f)
        assertEquals(30, PlaybackTuningHelper.crossfeedVirtualAngleDegrees(crossfeedPreset.crossfeedAngle))
        assertEquals("Crossfeed 48%", PlaybackTuningHelper.formatEffectsSummary(crossfeedPreset))

        val clamped = AudioEffectsState(
            crossfeedEnabled = true,
            crossfeedStrength = 2.0f,
            crossfeedAngle = Float.NaN,
        ).normalized()
        assertEquals(1.0f, clamped.crossfeedStrength, 0.001f)
        assertEquals(AudioEffectsState.DEFAULT_CROSSFEED_ANGLE, clamped.crossfeedAngle, 0.001f)
    }

    @Test
    fun `headphone routing helper resolves 8d speaker bypass, auto crossfeed, and media hook taps`() {
        val speakerRoute = AudioOutputRouteState(
            outputType = AudioOutputType.SPEAKER,
            deviceName = "Altavoz del teléfono",
            isHeadphonesConnected = false,
        )
        val btRoute = AudioOutputRouteState(
            outputType = AudioOutputType.BLUETOOTH_HEADSET,
            deviceName = "Aura Buds",
            isHeadphonesConnected = true,
        )
        val prefs = HeadphonePreferences(
            autoDisable8dOnSpeaker = true,
            autoEnableCrossfeedOnHeadphones = true,
            doubleTapNextEnabled = true,
            tripleTapPreviousEnabled = true,
        )
        val effectsWith8d = AudioEffectsState(spatial8dEnabled = true, crossfeedEnabled = false)

        val onSpeaker = HeadphoneRoutingHelper.resolveEffectiveRustEffects(effectsWith8d, speakerRoute, prefs)
        assertFalse(onSpeaker.spatial8dEnabled)
        assertTrue(onSpeaker.spatial8dBypassedBySpeaker)
        assertFalse(onSpeaker.crossfeedEnabled)

        val onHeadphones = HeadphoneRoutingHelper.resolveEffectiveRustEffects(effectsWith8d, btRoute, prefs)
        assertTrue(onHeadphones.spatial8dEnabled)
        assertFalse(onHeadphones.spatial8dBypassedBySpeaker)
        assertTrue(onHeadphones.crossfeedEnabled)
        assertTrue(onHeadphones.crossfeedAutoEnabledByHeadphones)

        assertEquals(MediaHookTapAction.TOGGLE_PLAY_PAUSE, HeadphoneRoutingHelper.resolveMediaHookTapAction(1, prefs))
        assertEquals(MediaHookTapAction.SKIP_NEXT, HeadphoneRoutingHelper.resolveMediaHookTapAction(2, prefs))
        assertEquals(MediaHookTapAction.SKIP_PREVIOUS, HeadphoneRoutingHelper.resolveMediaHookTapAction(3, prefs))
        assertEquals(
            MediaHookTapAction.TOGGLE_PLAY_PAUSE,
            HeadphoneRoutingHelper.resolveMediaHookTapAction(2, prefs.copy(doubleTapNextEnabled = false)),
        )
        assertEquals("Siempre previa", HeadphoneRoutingHelper.formatPreviousThresholdLabel(0L))
        assertEquals("3.5 s", HeadphoneRoutingHelper.formatPreviousThresholdLabel(3_500L))
    }

    @Test
    fun `playback tuning helper resolves independent and linked vocal pitch accurately`() {
        assertEquals(0.5f, PlaybackTuningHelper.clampPlaybackPitch(0.1f), 0.001f)
        assertEquals(2.0f, PlaybackTuningHelper.clampPlaybackPitch(3.5f), 0.001f)
        assertEquals(1.0f, PlaybackTuningHelper.clampPlaybackPitch(Float.NaN), 0.001f)
        assertEquals("1.15x", PlaybackTuningHelper.formatPitchLabel(1.15f))
        assertEquals("Voz natural", PlaybackTuningHelper.describeVoicePitch(1.0f))
        assertEquals("Voz más grave", PlaybackTuningHelper.describeVoicePitch(0.88f))
        assertEquals("Voz más aguda", PlaybackTuningHelper.describeVoicePitch(1.18f))

        // Tono de voz independiente de la velocidad
        val independent = PlaybackTuningHelper.resolvePlaybackParameters(
            tuning = PlaybackTuning(
                playbackSpeed = 1.30f,
                playbackPitch = 0.85f,
                linkPitchToSpeed = false,
            ),
            effects = AudioEffectsState(),
        )
        assertEquals(1.30f, independent.speed, 0.001f)
        assertEquals(0.85f, independent.pitch, 0.001f)

        // Modo vinculado: la voz sigue la velocidad (efecto cinta/vinilo)
        val linked = PlaybackTuningHelper.resolvePlaybackParameters(
            tuning = PlaybackTuning(
                playbackSpeed = 1.35f,
                playbackPitch = 0.80f,
                linkPitchToSpeed = true,
            ),
            effects = AudioEffectsState(),
        )
        assertEquals(1.35f, linked.speed, 0.001f)
        assertEquals(1.35f, linked.pitch, 0.001f)
    }

    @Test
    fun `sleep timer helper validates custom minutes, formats countdowns and evaluates auto stop`() {
        assertEquals(25, SleepTimerHelper.parseCustomMinutesOrNull(" 25 "))
        assertEquals(null, SleepTimerHelper.parseCustomMinutesOrNull("0"))
        assertEquals(null, SleepTimerHelper.parseCustomMinutesOrNull("999"))
        assertEquals(null, SleepTimerHelper.parseCustomMinutesOrNull("abc"))

        assertEquals("15 min", SleepTimerHelper.formatPresetLabel(15))
        assertEquals("1 h", SleepTimerHelper.formatPresetLabel(60))
        assertEquals("90 min", SleepTimerHelper.formatPresetLabel(90))
        assertEquals("05:00", SleepTimerHelper.formatCountdown(300_000L))
        assertEquals("1:05:02", SleepTimerHelper.formatCountdown(3_901_200L))

        val endOfTrackState = SleepTimerState(mode = SleepTimerMode.END_OF_TRACK)
        assertTrue(SleepTimerHelper.shouldBypassCrossfadeForSleepTimer(endOfTrackState, hasNextMediaItem = true))
        assertTrue(
            SleepTimerHelper.shouldPauseOnAutoTransition(
                mode = SleepTimerMode.END_OF_TRACK,
                previousIndex = 0,
                newIndex = 1,
                itemCount = 3,
                reasonIsAuto = true,
                reasonIsRepeat = false,
            ),
        )

        val endOfQueueState = SleepTimerState(mode = SleepTimerMode.END_OF_QUEUE)
        assertFalse(SleepTimerHelper.shouldBypassCrossfadeForSleepTimer(endOfQueueState, hasNextMediaItem = true))
        assertTrue(SleepTimerHelper.shouldBypassCrossfadeForSleepTimer(endOfQueueState, hasNextMediaItem = false))
        assertFalse(
            SleepTimerHelper.shouldPauseOnAutoTransition(
                mode = SleepTimerMode.END_OF_QUEUE,
                previousIndex = 0,
                newIndex = 1,
                itemCount = 3,
                reasonIsAuto = true,
                reasonIsRepeat = false,
            ),
        )
        assertTrue(
            SleepTimerHelper.shouldPauseOnAutoTransition(
                mode = SleepTimerMode.END_OF_QUEUE,
                previousIndex = 2,
                newIndex = 0,
                itemCount = 3,
                reasonIsAuto = true,
                reasonIsRepeat = false,
            ),
        )
        assertEquals(42_000L, SleepTimerHelper.computeRemainingMs(endElapsedMs = 142_000L, nowElapsedMs = 100_000L))
        assertEquals(0L, SleepTimerHelper.computeRemainingMs(endElapsedMs = 90_000L, nowElapsedMs = 100_000L))
    }

    @Test
    fun `process role resolver isolates main, player, and downloader processes`() {
        assertEquals(AuraProcessRole.MAIN, AuraProcessRoleResolver.resolve("com.auramusic.app"))
        assertEquals(AuraProcessRole.MAIN, AuraProcessRoleResolver.resolve(null))
        assertEquals(AuraProcessRole.PLAYER, AuraProcessRoleResolver.resolve("com.auramusic.app:player\u0000\u0000"))
        assertEquals(AuraProcessRole.DOWNLOADER, AuraProcessRoleResolver.resolve("com.auramusic.app.debug:downloader"))
    }

    @Test
    fun `memory trim policy clears visual caches on ui hidden and low memory pressure`() {
        val moderate = MemoryTrimPolicy.resolve(MemoryTrimPolicy.LEVEL_RUNNING_MODERATE)
        assertFalse(moderate.clearImageMemoryCache)
        assertFalse(moderate.clearDynamicColorCache)
        assertFalse(moderate.clearTransientControllerCaches)

        val uiHidden = MemoryTrimPolicy.resolve(MemoryTrimPolicy.LEVEL_UI_HIDDEN)
        assertTrue(uiHidden.clearImageMemoryCache)
        assertTrue(uiHidden.clearDynamicColorCache)
        assertFalse(uiHidden.clearTransientControllerCaches)

        val runningLow = MemoryTrimPolicy.resolve(MemoryTrimPolicy.LEVEL_RUNNING_LOW)
        assertTrue(runningLow.clearImageMemoryCache)
        assertTrue(runningLow.clearDynamicColorCache)

        val background = MemoryTrimPolicy.resolve(MemoryTrimPolicy.LEVEL_BACKGROUND)
        assertTrue(background.clearImageMemoryCache)
        assertTrue(background.clearDynamicColorCache)
        assertTrue(background.clearTransientControllerCaches)
    }

    @Test
    fun `system folder helper sanitizes filenames and disables system folder by default`() {
        val defaultState = SystemFolderStorageState()
        assertFalse(defaultState.isEnabled)
        assertEquals(SystemFolderHelper.DISPLAY_ROOT_PATH, defaultState.folderDisplayPath)

        val fileName = SystemFolderHelper.buildTrackAudioFileName(
            title = "Mi: Canción? / Demo*  ",
            artist = "Artista <Test>",
            extension = "FLAC",
            fingerprint = "abcdef123456",
        )
        assertEquals("Artista Test - Mi Canción Demo.flac", fileName)

        val unknownArtistName = SystemFolderHelper.buildTrackAudioFileName(
            title = "Instrumental",
            artist = TrackMetadataHelper.DEFAULT_ARTIST,
            extension = "mp3",
            fingerprint = "1234567890",
            disambiguate = true,
        )
        assertEquals("Instrumental_123456.mp3", unknownArtistName)

        assertTrue(SystemFolderHelper.isSupportedAudioFileName("tema.mp3"))
        assertTrue(SystemFolderHelper.isSupportedAudioFileName("pista.FLAC"))
        assertFalse(SystemFolderHelper.isSupportedAudioFileName(".nomedia"))
        assertFalse(SystemFolderHelper.isSupportedAudioFileName("aura_biblioteca.json"))
        assertFalse(SystemFolderHelper.isSupportedAudioFileName("descarga.mp3.tmp"))
    }
}
