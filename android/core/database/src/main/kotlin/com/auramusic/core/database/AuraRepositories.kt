package com.auramusic.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.EditedTrackMetadata
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import com.auramusic.core.model.TrackMetadataHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.auraSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aura_music_settings",
)

/**
 * Repositorio unificado de música y playlists que garantiza ejecutar escrituras y consultas
 * fuera del hilo principal usando [Dispatchers.IO].
 */
class MusicRepository(
    private val database: AuraDatabase,
) {
    private val trackDao = database.trackDao()
    private val playlistDao = database.playlistDao()

    fun observeAllTracks(): Flow<List<Track>> =
        trackDao.observeAllTracks().map { list -> list.map { it.toDomain() } }

    fun observeLikedTracks(): Flow<List<Track>> =
        trackDao.observeLikedTracks().map { list -> list.map { it.toDomain() } }

    fun observeLikedCount(): Flow<Int> = trackDao.observeLikedCount()

    fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observePlaylists().map { rows ->
            rows.map { row ->
                Playlist(
                    id = row.id,
                    name = row.name,
                    trackCount = row.trackCount,
                    coverFilePath = row.coverFilePath,
                    createdAt = row.createdAt,
                )
            }
        }

    fun observePlaylistName(playlistId: Long): Flow<String?> =
        playlistDao.observePlaylistById(playlistId).map { it?.name }

    fun observeTracksForPlaylist(playlistId: Long): Flow<List<Track>> =
        playlistDao.observeTracksForPlaylist(playlistId).map { list -> list.map { it.toDomain() } }

    suspend fun getAllTrackIdentities(): Pair<Set<String>, Set<String>> = withContext(Dispatchers.IO) {
        val rows = trackDao.getAllTrackIdentities()
        val uris = HashSet<String>(rows.size)
        val fingerprints = HashSet<String>(rows.size)
        for (row in rows) {
            uris.add(row.content_uri)
            fingerprints.add(row.track_fingerprint)
        }
        uris to fingerprints
    }

    suspend fun existsByUriOrFingerprint(uri: String, fingerprint: String): Boolean =
        withContext(Dispatchers.IO) {
            trackDao.existsByUriOrFingerprint(uri, fingerprint)
        }

    suspend fun findTrackByUriOrFingerprint(uri: String, fingerprint: String): Track? =
        withContext(Dispatchers.IO) {
            trackDao.findByUriOrFingerprint(uri, fingerprint)?.toDomain()
        }

    suspend fun insertTrackAndGet(track: Track): Track = withContext(Dispatchers.IO) {
        val insertedIds = trackDao.insertAll(
            listOf(
                TrackEntity(
                    contentUri = track.contentUri,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    coverFilePath = track.coverFilePath,
                    isProceduralCover = track.isProceduralCover,
                    isLiked = track.isLiked,
                    trackFingerprint = track.trackFingerprint,
                    addedAt = track.addedAt,
                ),
            ),
        )
        val rowId = insertedIds.firstOrNull() ?: -1L
        if (rowId > 0L) {
            trackDao.getById(rowId)?.toDomain() ?: track.copy(id = rowId)
        } else {
            trackDao.findByUriOrFingerprint(track.contentUri, track.trackFingerprint)?.toDomain()
                ?: track
        }
    }

    suspend fun insertTracks(tracks: List<Track>): Unit = withContext(Dispatchers.IO) {
        if (tracks.isEmpty()) return@withContext
        trackDao.insertAll(
            tracks.map { track ->
                TrackEntity(
                    contentUri = track.contentUri,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    coverFilePath = track.coverFilePath,
                    isProceduralCover = track.isProceduralCover,
                    isLiked = track.isLiked,
                    trackFingerprint = track.trackFingerprint,
                    addedAt = track.addedAt,
                )
            },
        )
    }

    suspend fun toggleLike(trackId: Long): Unit = withContext(Dispatchers.IO) {
        trackDao.toggleLike(trackId)
    }

    suspend fun getAllReferencedCoverPaths(): List<String> = withContext(Dispatchers.IO) {
        trackDao.getAllReferencedCoverPaths()
    }

    /**
     * Actualiza en Room el nombre de la canción y el artista tras normalizar ambos campos.
     */
    suspend fun updateTrackMetadata(
        trackId: Long,
        title: String,
        artist: String,
    ): EditedTrackMetadata? = withContext(Dispatchers.IO) {
        val normalized = TrackMetadataHelper.normalizeOrNull(title, artist) ?: return@withContext null
        trackDao.updateMetadata(
            trackId = trackId,
            title = normalized.title,
            artist = normalized.artist,
        )
        normalized
    }

    /**
     * Actualiza la carátula de una pista en Room y elimina la anterior si queda huérfana.
     */
    suspend fun updateTrackCover(
        trackId: Long,
        newCoverPath: String,
        isProcedural: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        val previousPath = trackDao.getCoverFilePathById(trackId)
        trackDao.updateCoverPath(trackId, newCoverPath, isProcedural)

        if (!previousPath.isNullOrBlank() &&
            previousPath != newCoverPath &&
            trackDao.countTracksUsingCoverPath(previousPath) == 0
        ) {
            val previousFile = java.io.File(previousPath)
            if (previousFile.parentFile?.name == "covers" && previousFile.exists()) {
                runCatching { previousFile.delete() }
            }
        }
    }

    /**
     * Elimina la pista de Room y borra su carátula en caché si ninguna otra canción la utiliza.
     */
    suspend fun deleteTrack(trackId: Long): Unit = withContext(Dispatchers.IO) {
        val coverPath = trackDao.getCoverFilePathById(trackId)
        trackDao.deleteById(trackId)

        if (!coverPath.isNullOrBlank() && trackDao.countTracksUsingCoverPath(coverPath) == 0) {
            val coverFile = java.io.File(coverPath)
            if (coverFile.parentFile?.name == "covers" && coverFile.exists()) {
                runCatching { coverFile.delete() }
            }
        }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        playlistDao.insertPlaylist(
            PlaylistEntity(
                name = name.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deletePlaylist(playlistId: Long): Unit = withContext(Dispatchers.IO) {
        playlistDao.deletePlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(playlistId: Long, trackId: Long): Unit =
        withContext(Dispatchers.IO) {
            playlistDao.addTrackToPlaylist(
                PlaylistTrackCrossRef(
                    playlistId = playlistId,
                    trackId = trackId,
                ),
            )
        }

    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long): Unit =
        withContext(Dispatchers.IO) {
            playlistDao.removeTrackFromPlaylist(playlistId, trackId)
        }

    companion object {
        fun buildDatabase(context: Context): AuraDatabase = Room.databaseBuilder(
            context.applicationContext,
            AuraDatabase::class.java,
            "aura_music.db",
        )
            .addMigrations(*AuraMigrations.ALL_MIGRATIONS)
            .build()
    }
}

/**
 * Repositorio de preferencias visuales (color de acento, colores dinámicos de carátula y
 * animaciones de eliminación en Rust) respaldado por Jetpack DataStore.
 */
class ThemePreferencesRepository(
    private val context: Context,
) {
    private val accentKey = stringPreferencesKey("accent_color")
    private val dynamicCoverColorsKey = booleanPreferencesKey("dynamic_cover_colors")
    private val deleteAnimationsKey = booleanPreferencesKey("delete_animations_enabled")

    val accentColor: Flow<AppAccentColor> = context.auraSettingsDataStore.data.map { prefs ->
        AppAccentColor.fromName(prefs[accentKey])
    }

    val dynamicCoverColorsEnabled: Flow<Boolean> = context.auraSettingsDataStore.data.map { prefs ->
        prefs[dynamicCoverColorsKey] ?: true
    }

    val deleteAnimationsEnabled: Flow<Boolean> = context.auraSettingsDataStore.data.map { prefs ->
        prefs[deleteAnimationsKey] ?: true
    }

    suspend fun setAccentColor(color: AppAccentColor) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[accentKey] = color.name
            }
        }
    }

    suspend fun setDynamicCoverColorsEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[dynamicCoverColorsKey] = enabled
            }
        }
    }

    suspend fun setDeleteAnimationsEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[deleteAnimationsKey] = enabled
            }
        }
    }
}

/**
 * Repositorio de preferencias del ecualizador de 10 bandas en Jetpack DataStore.
 * Guarda estado encendido/apagado, preset activo y ganancias por banda.
 */
class EqualizerPreferencesRepository(
    private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("eq_enabled")
    private val presetKey = stringPreferencesKey("eq_preset_id")
    private val bandsKey = stringPreferencesKey("eq_bands_db")

    val equalizerState: Flow<EqualizerState> = context.auraSettingsDataStore.data.map { prefs ->
        val enabled = prefs[enabledKey] ?: false
        val presetId = prefs[presetKey] ?: EqualizerState.PRESET_FLAT_ID
        val rawBands = prefs[bandsKey]
            ?.split(',')
            ?.map { token -> token.trim().toFloatOrNull() ?: 0f }
            ?: List(EqualizerState.BAND_COUNT) { 0f }

        EqualizerState(
            isEnabled = enabled,
            bandGainsDb = rawBands,
            activePresetId = presetId,
        ).normalized()
    }

    suspend fun saveEqualizerState(state: EqualizerState) {
        val normalized = state.normalized()
        val serializedBands = normalized.bandGainsDb.joinToString(",") { value ->
            String.format(java.util.Locale.US, "%.2f", value)
        }
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[enabledKey] = normalized.isEnabled
                prefs[presetKey] = normalized.activePresetId
                prefs[bandsKey] = serializedBands
            }
        }
    }
}

/**
 * Repositorio de preferencias de auto-actualización y calidad de audio para `yt-dlp` en DataStore.
 */
class DownloaderPreferencesRepository(
    private val context: Context,
) {
    private val autoUpdateKey = booleanPreferencesKey("ytdlp_auto_update")
    private val lastCheckKey = androidx.datastore.preferences.core.longPreferencesKey("ytdlp_last_check_ms")
    private val audioQualityKey = stringPreferencesKey("ytdlp_audio_quality")

    val autoUpdateEnabled: Flow<Boolean> = context.auraSettingsDataStore.data.map { prefs ->
        prefs[autoUpdateKey] ?: true
    }

    val lastUpdateCheckEpochMs: Flow<Long> = context.auraSettingsDataStore.data.map { prefs ->
        prefs[lastCheckKey] ?: 0L
    }

    val audioDownloadQuality: Flow<com.auramusic.core.model.AudioDownloadQuality> =
        context.auraSettingsDataStore.data.map { prefs ->
            com.auramusic.core.model.AudioDownloadQuality.fromKey(prefs[audioQualityKey])
        }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[autoUpdateKey] = enabled
            }
        }
    }

    suspend fun setLastUpdateCheckEpochMs(epochMs: Long) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[lastCheckKey] = epochMs
            }
        }
    }

    suspend fun setAudioDownloadQuality(quality: com.auramusic.core.model.AudioDownloadQuality) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[audioQualityKey] = quality.key
            }
        }
    }
}

/**
 * Repositorio de preferencias de transición Crossfade y velocidad de reproducción en DataStore.
 */
class PlaybackPreferencesRepository(
    private val context: Context,
) {
    private val crossfadeEnabledKey = booleanPreferencesKey("playback_crossfade_enabled")
    private val crossfadeSecondsKey = intPreferencesKey("playback_crossfade_seconds")
    private val playbackSpeedKey = floatPreferencesKey("playback_speed")

    val playbackTuning: Flow<PlaybackTuning> = context.auraSettingsDataStore.data.map { prefs ->
        PlaybackTuning(
            crossfadeEnabled = prefs[crossfadeEnabledKey] ?: false,
            crossfadeSeconds = prefs[crossfadeSecondsKey] ?: PlaybackTuning.DEFAULT_CROSSFADE_SECONDS,
            playbackSpeed = prefs[playbackSpeedKey] ?: PlaybackTuning.DEFAULT_PLAYBACK_SPEED,
        ).normalized()
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[crossfadeEnabledKey] = enabled
            }
        }
    }

    suspend fun setCrossfadeSeconds(seconds: Int) {
        val safeSeconds = PlaybackTuningHelper.clampCrossfadeSeconds(seconds)
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[crossfadeSecondsKey] = safeSeconds
            }
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = PlaybackTuningHelper.clampPlaybackSpeed(speed)
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[playbackSpeedKey] = safeSpeed
            }
        }
    }
}

/**
 * Repositorio de preferencias de efectos de audio en Rust (Reverb estéreo, Audio 8D y modo Slowed) en DataStore.
 */
class AudioEffectsPreferencesRepository(
    private val context: Context,
) {
    private val reverbEnabledKey = booleanPreferencesKey("fx_reverb_enabled")
    private val reverbRoomSizeKey = floatPreferencesKey("fx_reverb_room_size")
    private val reverbDampingKey = floatPreferencesKey("fx_reverb_damping")
    private val reverbWetMixKey = floatPreferencesKey("fx_reverb_wet_mix")
    private val reverbWidthKey = floatPreferencesKey("fx_reverb_width")
    private val slowedEnabledKey = booleanPreferencesKey("fx_slowed_enabled")
    private val slowedSpeedKey = floatPreferencesKey("fx_slowed_speed")
    private val slowedPreservePitchKey = booleanPreferencesKey("fx_slowed_preserve_pitch")
    private val slowedWarmthKey = floatPreferencesKey("fx_slowed_warmth")
    private val spatial8dEnabledKey = booleanPreferencesKey("fx_spatial_8d_enabled")
    private val spatial8dSpeedKey = floatPreferencesKey("fx_spatial_8d_speed")
    private val spatial8dDepthKey = floatPreferencesKey("fx_spatial_8d_depth")
    private val presetIdKey = stringPreferencesKey("fx_preset_id")

    val audioEffectsState: Flow<AudioEffectsState> = context.auraSettingsDataStore.data.map { prefs ->
        AudioEffectsState(
            reverbEnabled = prefs[reverbEnabledKey] ?: false,
            reverbRoomSize = prefs[reverbRoomSizeKey] ?: AudioEffectsState.DEFAULT_ROOM_SIZE,
            reverbDamping = prefs[reverbDampingKey] ?: AudioEffectsState.DEFAULT_DAMPING,
            reverbWetMix = prefs[reverbWetMixKey] ?: AudioEffectsState.DEFAULT_WET_MIX,
            reverbWidth = prefs[reverbWidthKey] ?: AudioEffectsState.DEFAULT_WIDTH,
            slowedEnabled = prefs[slowedEnabledKey] ?: false,
            slowedSpeed = prefs[slowedSpeedKey] ?: AudioEffectsState.DEFAULT_SLOWED_SPEED,
            slowedPreservePitch = prefs[slowedPreservePitchKey] ?: false,
            slowedWarmth = prefs[slowedWarmthKey] ?: AudioEffectsState.DEFAULT_SLOWED_WARMTH,
            spatial8dEnabled = prefs[spatial8dEnabledKey] ?: false,
            spatial8dSpeed = prefs[spatial8dSpeedKey] ?: AudioEffectsState.DEFAULT_SPATIAL_8D_SPEED,
            spatial8dDepth = prefs[spatial8dDepthKey] ?: AudioEffectsState.DEFAULT_SPATIAL_8D_DEPTH,
            activePresetId = prefs[presetIdKey] ?: AudioEffectsState.PRESET_OFF_ID,
        ).normalized()
    }

    suspend fun saveAudioEffectsState(state: AudioEffectsState) {
        val normalized = state.normalized()
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[reverbEnabledKey] = normalized.reverbEnabled
                prefs[reverbRoomSizeKey] = normalized.reverbRoomSize
                prefs[reverbDampingKey] = normalized.reverbDamping
                prefs[reverbWetMixKey] = normalized.reverbWetMix
                prefs[reverbWidthKey] = normalized.reverbWidth
                prefs[slowedEnabledKey] = normalized.slowedEnabled
                prefs[slowedSpeedKey] = normalized.slowedSpeed
                prefs[slowedPreservePitchKey] = normalized.slowedPreservePitch
                prefs[slowedWarmthKey] = normalized.slowedWarmth
                prefs[spatial8dEnabledKey] = normalized.spatial8dEnabled
                prefs[spatial8dSpeedKey] = normalized.spatial8dSpeed
                prefs[spatial8dDepthKey] = normalized.spatial8dDepth
                prefs[presetIdKey] = normalized.activePresetId
            }
        }
    }
}


