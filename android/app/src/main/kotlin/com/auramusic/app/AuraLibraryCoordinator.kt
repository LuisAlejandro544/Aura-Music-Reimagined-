package com.auramusic.app

import android.net.Uri
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.ThemePreferencesRepository
import com.auramusic.core.downloader.YtDlpAudioDownloader
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.ArtistPlaylist
import com.auramusic.core.model.ArtistPlaylistBuilder
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import com.auramusic.core.player.AuraPlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinador modular de biblioteca, playlists, importación SAF, descargas yt-dlp,
 * edición local de metadatos/carátulas y preferencias visuales.
 */
class AuraLibraryCoordinator(
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val downloaderPreferencesRepository: DownloaderPreferencesRepository,
    private val audioImporter: AudioImporter,
    private val ytDlpAudioDownloader: YtDlpAudioDownloader,
    private val playerController: AuraPlayerController,
    private val onOpenPlayerRequested: () -> Unit,
    private val onUiMessage: (String) -> Unit,
) {
    val accentColor: StateFlow<AppAccentColor> = themePreferencesRepository.accentColor
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AppAccentColor.AMETHYST,
        )

    val dynamicCoverColorsEnabled: StateFlow<Boolean> =
        themePreferencesRepository.dynamicCoverColorsEnabled
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = true,
            )

    val deleteAnimationsEnabled: StateFlow<Boolean> =
        themePreferencesRepository.deleteAnimationsEnabled
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = true,
            )

    val tracks: StateFlow<List<Track>> = musicRepository.observeAllTracks()
        .onEach { updated -> playerController.refreshTrackSnapshots(updated) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val likedTracks: StateFlow<List<Track>> = musicRepository.observeLikedTracks()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val likedCount: StateFlow<Int> = musicRepository.observeLikedCount()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = 0,
        )

    val playlists: StateFlow<List<Playlist>> = musicRepository.observePlaylists()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val artistPlaylists: StateFlow<List<ArtistPlaylist>> = tracks
        .map { currentTracks -> ArtistPlaylistBuilder.buildFromTracks(currentTracks) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val importProgress: StateFlow<ImportProgress> = audioImporter.progress

    val downloadState: StateFlow<DownloadState> = ytDlpAudioDownloader.downloadState

    val downloaderEngineStatus: StateFlow<DownloaderEngineStatus> = ytDlpAudioDownloader.engineStatus

    fun refreshDownloaderStatusInBackground() {
        scope.launch(Dispatchers.IO) {
            runCatching { ytDlpAudioDownloader.refreshEngineStatus() }
        }
    }

    fun observePlaylistName(playlistId: Long): Flow<String?> =
        musicRepository.observePlaylistName(playlistId)

    fun observeTracksForPlaylist(playlistId: Long): Flow<List<Track>> =
        musicRepository.observeTracksForPlaylist(playlistId)

    fun observeArtistPlaylist(artistKey: String): Flow<ArtistPlaylist?> {
        val targetKey = ArtistPlaylistBuilder.normalizeArtistKey(artistKey)
        return artistPlaylists.map { list -> list.firstOrNull { it.artistKey == targetKey } }
    }

    fun importFiles(uris: List<Uri>) {
        scope.launch {
            audioImporter.importDocumentUris(
                uris = uris,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun importFolder(treeUri: Uri) {
        scope.launch {
            audioImporter.importFolderTree(
                treeUri = treeUri,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun dismissImportMessage() {
        audioImporter.clearLastMessage()
    }

    fun inspectDownloadUrl(url: String) {
        scope.launch {
            ytDlpAudioDownloader.inspectAudioUrl(rawUrl = url)
        }
    }

    fun confirmPendingDownload() {
        ytDlpAudioDownloader.startBackgroundConfirmedDownload(
            accentArgb = accentColor.value.primaryArgb.toInt(),
        )
    }

    fun downloadAudioFromUrl(url: String) {
        ytDlpAudioDownloader.startBackgroundUrlDownload(
            rawUrl = url,
            accentArgb = accentColor.value.primaryArgb.toInt(),
        )
    }

    fun cancelActiveDownload() {
        ytDlpAudioDownloader.requestCancelDownload()
    }

    fun checkDownloaderUpdates() {
        scope.launch {
            ytDlpAudioDownloader.checkAndUpdateEngines(force = true)
        }
    }

    fun setDownloaderAutoUpdate(enabled: Boolean) {
        scope.launch {
            downloaderPreferencesRepository.setAutoUpdateEnabled(enabled)
            ytDlpAudioDownloader.refreshEngineStatus()
        }
    }

    fun setDownloaderAudioQuality(quality: AudioDownloadQuality) {
        scope.launch {
            ytDlpAudioDownloader.setPreferredAudioQuality(quality)
        }
    }

    fun selectDownloaderPreviewFormat(formatId: String) {
        ytDlpAudioDownloader.selectPreviewAudioFormat(formatId)
    }

    fun dismissDownloadStatus() {
        ytDlpAudioDownloader.clearDownloadStatus()
    }

    fun dismissDownloaderUpdateMessage() {
        ytDlpAudioDownloader.clearUpdateMessage()
    }

    fun openExternalAudioUri(uri: Uri) {
        scope.launch {
            val importedTrack = audioImporter.importExternalAudioUri(
                uri = uri,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
            if (importedTrack != null) {
                val currentTracks = musicRepository.observeAllTracks().first()
                val queue = if (currentTracks.any { it.id == importedTrack.id }) {
                    currentTracks
                } else {
                    listOf(importedTrack) + currentTracks
                }
                val startIndex = queue.indexOfFirst { it.id == importedTrack.id }.coerceAtLeast(0)
                playerController.playQueue(queue, startIndex)
                onOpenPlayerRequested()
                onUiMessage("Abriendo en Aura Music: ${importedTrack.title}")
            } else {
                onUiMessage("No se pudo abrir el archivo de audio seleccionado.")
            }
        }
    }

    fun toggleLike(trackId: Long) {
        scope.launch {
            musicRepository.toggleLike(trackId)
        }
    }

    fun deleteTrack(trackId: Long) {
        scope.launch {
            val snapshot = tracks.value
            val targetTrack = snapshot.firstOrNull { it.id == trackId }
            musicRepository.deleteTrack(trackId)
            audioImporter.cleanupOrphanCovers()
            if (targetTrack != null && snapshot.none { it.id != trackId && it.contentUri == targetTrack.contentUri }) {
                audioImporter.deleteOwnedDownloadFileIfExists(targetTrack.contentUri)
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        scope.launch {
            musicRepository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch {
            musicRepository.deletePlaylist(playlistId)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        scope.launch {
            musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        if (name.isBlank()) return
        scope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        scope.launch {
            musicRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun selectAccentColor(color: AppAccentColor) {
        scope.launch {
            themePreferencesRepository.setAccentColor(color)
        }
    }

    fun setDynamicCoverColorsEnabled(enabled: Boolean) {
        scope.launch {
            themePreferencesRepository.setDynamicCoverColorsEnabled(enabled)
        }
    }

    fun setDeleteAnimationsEnabled(enabled: Boolean) {
        scope.launch {
            themePreferencesRepository.setDeleteAnimationsEnabled(enabled)
        }
    }

    fun updateTrackMetadata(trackId: Long, title: String, artist: String) {
        scope.launch {
            val updated = musicRepository.updateTrackMetadata(
                trackId = trackId,
                title = title,
                artist = artist,
            )
            if (updated != null) {
                onUiMessage("Metadatos actualizados: ${updated.title}")
            }
        }
    }

    fun setCustomTrackCover(trackId: Long, imageUri: Uri) {
        scope.launch {
            val webpPath = audioImporter.saveCustomCoverAsLosslessWebp(
                trackId = trackId,
                imageUri = imageUri,
            )
            if (webpPath != null) {
                musicRepository.updateTrackCover(
                    trackId = trackId,
                    newCoverPath = webpPath,
                    isProcedural = false,
                )
            }
        }
    }
}
