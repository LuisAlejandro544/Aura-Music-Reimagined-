package com.auramusic.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
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

    suspend fun existsByUriOrFingerprint(uri: String, fingerprint: String): Boolean =
        withContext(Dispatchers.IO) {
            trackDao.existsByUriOrFingerprint(uri, fingerprint)
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

    suspend fun deleteTrack(trackId: Long): Unit = withContext(Dispatchers.IO) {
        trackDao.deleteById(trackId)
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
        ).build()
    }
}

/**
 * Repositorio de preferencias visuales (color de acento de la app) respaldado por Jetpack DataStore.
 */
class ThemePreferencesRepository(
    private val context: Context,
) {
    private val accentKey = stringPreferencesKey("accent_color")

    val accentColor: Flow<AppAccentColor> = context.auraSettingsDataStore.data.map { prefs ->
        AppAccentColor.fromName(prefs[accentKey])
    }

    suspend fun setAccentColor(color: AppAccentColor) {
        withContext(Dispatchers.IO) {
            context.auraSettingsDataStore.edit { prefs ->
                prefs[accentKey] = color.name
            }
        }
    }
}
