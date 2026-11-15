package com.auramusic.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Proyección ligera de identidad de pista para verificar duplicados por lote en memoria.
 */
data class TrackIdentityRow(
    val content_uri: String,
    val track_fingerprint: String,
)

/**
 * DAO de acceso a pistas locales y estado de "Me gusta".
 */
@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY added_at DESC, title ASC")
    fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE is_liked = 1 ORDER BY added_at DESC")
    fun observeLikedTracks(): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks WHERE is_liked = 1")
    fun observeLikedCount(): Flow<Int>

    @Query("SELECT content_uri, track_fingerprint FROM tracks")
    suspend fun getAllTrackIdentities(): List<TrackIdentityRow>

    @Query("SELECT EXISTS(SELECT 1 FROM tracks WHERE content_uri = :uri OR track_fingerprint = :fingerprint)")
    suspend fun existsByUriOrFingerprint(uri: String, fingerprint: String): Boolean

    @Query("SELECT * FROM tracks WHERE content_uri = :uri OR track_fingerprint = :fingerprint LIMIT 1")
    suspend fun findByUriOrFingerprint(uri: String, fingerprint: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getById(trackId: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tracks: List<TrackEntity>): List<Long>

    @Query("UPDATE tracks SET is_liked = NOT is_liked WHERE id = :trackId")
    suspend fun toggleLike(trackId: Long)

    @Query("UPDATE tracks SET title = :title, artist = :artist WHERE id = :trackId")
    suspend fun updateMetadata(trackId: Long, title: String, artist: String)

    @Query("UPDATE tracks SET cover_file_path = :coverPath, is_procedural_cover = :isProcedural WHERE id = :trackId")
    suspend fun updateCoverPath(trackId: Long, coverPath: String, isProcedural: Boolean)

    @Query("SELECT cover_file_path FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getCoverFilePathById(trackId: Long): String?

    @Query("SELECT COUNT(*) FROM tracks WHERE cover_file_path = :coverPath")
    suspend fun countTracksUsingCoverPath(coverPath: String): Int

    @Query("SELECT cover_file_path FROM tracks WHERE cover_file_path IS NOT NULL")
    suspend fun getAllReferencedCoverPaths(): List<String>

    @Query("SELECT * FROM tracks ORDER BY added_at DESC, title ASC")
    suspend fun getAllTracksOnce(): List<TrackEntity>

    @Query("UPDATE tracks SET content_uri = :contentUri, cover_file_path = :coverFilePath WHERE id = :trackId")
    suspend fun updateStorageLocation(trackId: Long, contentUri: String, coverFilePath: String?)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteById(trackId: Long)
}

/**
 * DAO para crear, listar y administrar playlists del usuario.
 */
@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.id, p.name, p.created_at,
               COUNT(pt.track_id) AS track_count,
               (SELECT t.cover_file_path FROM tracks t
                INNER JOIN playlist_tracks pt2 ON pt2.track_id = t.id
                WHERE pt2.playlist_id = p.id
                ORDER BY pt2.added_at ASC LIMIT 1) AS cover_file_path
        FROM playlists p
        LEFT JOIN playlist_tracks pt ON pt.playlist_id = p.id
        GROUP BY p.id
        ORDER BY p.created_at DESC
        """,
    )
    fun observePlaylists(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists ORDER BY created_at DESC")
    suspend fun getAllPlaylistsOnce(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_tracks ORDER BY added_at ASC")
    suspend fun getAllCrossRefsOnce(): List<PlaylistTrackCrossRef>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun observePlaylistById(playlistId: Long): Flow<PlaylistEntity?>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN playlist_tracks pt ON pt.track_id = t.id
        WHERE pt.playlist_id = :playlistId
        ORDER BY pt.added_at ASC
        """,
    )
    fun observeTracksForPlaylist(playlistId: Long): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)

    @Query("DELETE FROM playlist_tracks WHERE playlist_id = :playlistId AND track_id = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: Long)
}

/**
 * Base de datos local Room de Aura Music.
 * Exporta esquemas JSON para auditar cambios entre versiones y asegurar migraciones no destructivas.
 */
@Database(
    entities = [TrackEntity::class, PlaylistEntity::class, PlaylistTrackCrossRef::class],
    version = AuraMigrations.CURRENT_DATABASE_VERSION,
    exportSchema = true,
)
abstract class AuraDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
}
