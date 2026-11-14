package com.auramusic.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.auramusic.core.model.Track

/**
 * Entidad Room que persiste cada canción importada por el usuario.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["content_uri"], unique = true),
        Index(value = ["track_fingerprint"]),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "content_uri")
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "cover_file_path")
    val coverFilePath: String?,
    @ColumnInfo(name = "is_procedural_cover")
    val isProceduralCover: Boolean,
    @ColumnInfo(name = "is_liked")
    val isLiked: Boolean = false,
    @ColumnInfo(name = "track_fingerprint")
    val trackFingerprint: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
) {
    fun toDomain(): Track = Track(
        id = id,
        contentUri = contentUri,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverFilePath = coverFilePath,
        isProceduralCover = isProceduralCover,
        isLiked = isLiked,
        trackFingerprint = trackFingerprint,
        addedAt = addedAt,
    )
}

/**
 * Entidad Room para playlists creadas por el usuario.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * Tabla puente muchos-a-muchos entre playlists y canciones con eliminación en cascada.
 */
@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlist_id", "track_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlist_id"]),
        Index(value = ["track_id"]),
    ],
)
data class PlaylistTrackCrossRef(
    @ColumnInfo(name = "playlist_id")
    val playlistId: Long,
    @ColumnInfo(name = "track_id")
    val trackId: Long,
    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Proyección SQL ligera para mostrar playlists con su conteo de pistas y carátula principal.
 */
data class PlaylistSummaryRow(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "track_count")
    val trackCount: Int,
    @ColumnInfo(name = "cover_file_path")
    val coverFilePath: String?,
)
