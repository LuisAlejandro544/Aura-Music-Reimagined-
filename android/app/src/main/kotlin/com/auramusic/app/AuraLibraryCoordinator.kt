package com.auramusic.app

import android.content.Context
import android.net.Uri
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.ThemePreferencesRepository
import com.auramusic.core.downloader.AuraDownloaderRemoteClient
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.importer.AuraSystemStorageManager
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.ArtistPlaylist
import com.auramusic.core.model.ArtistPlaylistBuilder
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.SystemFolderStorageState
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
 * Coordinador modular de biblioteca, playlists, importación SAF, descargas aisladas vía IPC
 * (`AuraDownloaderRemoteClient`), edición local de metadatos/carátulas y preferencias visuales.
 */
class AuraLibraryCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val musicRepository: MusicRepository,
    private val themePreferencesRepository: ThemePreferencesRepository,
    private val downloaderPreferencesRepository: DownloaderPreferencesRepository,
    private val audioImporter: AudioImporter,
    private val systemStorageManager: AuraSystemStorageManager,
    private val downloaderClient: AuraDownloaderRemoteClient,
    private val playerController: AuraPlayerController,
    private val onOpenPlayerRequested: () -> Unit,
    private val onUiMessage: (String) -> Unit,
) {
    @Volatile
    private var pendingEnableSystemFolder = false
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

    // WhileSubscribed(5_000) evita recalcular mapas y colecciones de biblioteca cuando la UI no está activa.
    val tracks: StateFlow<List<Track>> = musicRepository.observeAllTracks()
        .onEach { updated -> playerController.refreshTrackSnapshots(updated) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val likedTracks: StateFlow<List<Track>> = musicRepository.observeLikedTracks()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val likedCount: StateFlow<Int> = musicRepository.observeLikedCount()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0,
        )

    val playlists: StateFlow<List<Playlist>> = musicRepository.observePlaylists()
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val artistPlaylists: StateFlow<List<ArtistPlaylist>> = tracks
        .map { currentTracks -> ArtistPlaylistBuilder.buildFromTracks(currentTracks) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    val importProgress: StateFlow<ImportProgress> = audioImporter.progress

    val downloadState: StateFlow<DownloadState> = downloaderClient.downloadState

    val downloaderEngineStatus: StateFlow<DownloaderEngineStatus> = downloaderClient.engineStatus

    val systemFolderState: StateFlow<SystemFolderStorageState> = systemStorageManager.state

    /**
     * Lee el estado en caché de versiones del descargador e importa cualquier pista terminada
     * pendiente en `pending_imports/` sin arrancar Python/Chaquopy en el proceso principal.
     */
    fun refreshDownloaderStatusInBackground() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                downloaderClient.refreshCachedStatus()
                val drained = downloaderClient.drainPendingImports(accentColor.value.primaryArgb.toInt())
                if (drained > 0) {
                    systemStorageManager.syncJsonSnapshotsIfEnabled()
                }
            }
        }
    }

    /**
     * Comprueba permisos y, si la carpeta pública `Aura Music` está activa, escanea
     * `Aura Music/Música` al entrar o volver a la app para detectar canciones copiadas manualmente.
     */
    fun refreshSystemFolderAndAutoSync() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                systemStorageManager.refreshState()
                val hasPermission = AuraSystemStorageManager.hasStoragePermission(appContext)
                if (pendingEnableSystemFolder && hasPermission) {
                    pendingEnableSystemFolder = false
                    val result = systemStorageManager.enableAndMigrate(
                        accentArgb = accentColor.value.primaryArgb.toInt(),
                    )
                    result.statusMessage?.let(onUiMessage)
                    return@launch
                }

                val discovered = systemStorageManager.syncOnAppEntryOrManual(
                    accentArgb = accentColor.value.primaryArgb.toInt(),
                    migrateRemainingTracks = false,
                )
                if (discovered > 0) {
                    val label = if (discovered == 1) "canción nueva detectada" else "canciones nuevas detectadas"
                    onUiMessage("Aura Music: $discovered $label en la carpeta del sistema.")
                }
            }
        }
    }

    fun setSystemFolderEnabled(
        enabled: Boolean,
        requestPermission: () -> Unit,
    ) {
        if (!enabled) {
            pendingEnableSystemFolder = false
            scope.launch(Dispatchers.IO) {
                systemStorageManager.disableSystemFolder()
            }
            return
        }

        if (!AuraSystemStorageManager.hasStoragePermission(appContext)) {
            pendingEnableSystemFolder = true
            requestPermission()
            return
        }

        pendingEnableSystemFolder = false
        scope.launch(Dispatchers.IO) {
            val result = systemStorageManager.enableAndMigrate(
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
            result.statusMessage?.let(onUiMessage)
        }
    }

    fun onSystemFolderPermissionResult(granted: Boolean) {
        scope.launch(Dispatchers.IO) {
            systemStorageManager.refreshState()
            val actuallyGranted = granted || AuraSystemStorageManager.hasStoragePermission(appContext)
            pendingEnableSystemFolder = false
            if (actuallyGranted) {
                val result = systemStorageManager.enableAndMigrate(
                    accentArgb = accentColor.value.primaryArgb.toInt(),
                )
                result.statusMessage?.let(onUiMessage)
            } else {
                onUiMessage("Permiso de almacenamiento denegado. La carpeta Aura Music sigue desactivada.")
            }
        }
    }

    fun syncSystemFolderNow() {
        scope.launch(Dispatchers.IO) {
            systemStorageManager.syncOnAppEntryOrManual(
                accentArgb = accentColor.value.primaryArgb.toInt(),
                migrateRemainingTracks = true,
            )
            systemStorageManager.state.value.statusMessage?.let(onUiMessage)
        }
    }

    fun setDownloaderUiForeground(inForeground: Boolean) {
        downloaderClient.setUiForeground(inForeground)
    }

    fun setDownloaderScreenActive(active: Boolean) {
        downloaderClient.setScreenActive(active)
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
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun importFolder(treeUri: Uri) {
        scope.launch {
            audioImporter.importFolderTree(
                treeUri = treeUri,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun dismissImportMessage() {
        audioImporter.clearLastMessage()
    }

    fun inspectDownloadUrl(url: String) {
        downloaderClient.inspectAudioUrl(rawUrl = url)
    }

    fun confirmPendingDownload() {
        downloaderClient.startBackgroundConfirmedDownload(
            accentArgb = accentColor.value.primaryArgb.toInt(),
        )
    }

    fun downloadAudioFromUrl(url: String) {
        downloaderClient.startBackgroundUrlDownload(
            rawUrl = url,
            accentArgb = accentColor.value.primaryArgb.toInt(),
        )
    }

    fun cancelActiveDownload() {
        downloaderClient.requestCancelDownload()
    }

    fun checkDownloaderUpdates() {
        downloaderClient.checkAndUpdateEngines(force = true)
    }

    fun setDownloaderAutoUpdate(enabled: Boolean) {
        scope.launch {
            downloaderPreferencesRepository.setAutoUpdateEnabled(enabled)
            downloaderClient.refreshCachedStatus()
        }
    }

    fun setDownloaderAudioQuality(quality: AudioDownloadQuality) {
        scope.launch {
            downloaderClient.setPreferredAudioQuality(quality)
        }
    }

    fun selectDownloaderPreviewFormat(formatId: String) {
        downloaderClient.selectPreviewAudioFormat(formatId)
    }

    fun dismissDownloadStatus() {
        downloaderClient.clearDownloadStatus()
    }

    fun dismissDownloaderUpdateMessage() {
        downloaderClient.clearUpdateMessage()
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
                systemStorageManager.syncJsonSnapshotsIfEnabled()
            } else {
                onUiMessage("No se pudo abrir el archivo de audio seleccionado.")
            }
        }
    }

    fun toggleLike(trackId: Long) {
        scope.launch {
            musicRepository.toggleLike(trackId)
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun deleteTrack(trackId: Long) {
        scope.launch {
            val snapshot = musicRepository.observeAllTracks().first()
            val targetTrack = snapshot.firstOrNull { it.id == trackId }
            musicRepository.deleteTrack(trackId)
            audioImporter.cleanupOrphanCovers()
            if (targetTrack != null && snapshot.none { it.id != trackId && it.contentUri == targetTrack.contentUri }) {
                audioImporter.deleteOwnedDownloadFileIfExists(targetTrack.contentUri)
            }
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        scope.launch {
            musicRepository.createPlaylist(name)
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun deletePlaylist(playlistId: Long) {
        scope.launch {
            musicRepository.deletePlaylist(playlistId)
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        scope.launch {
            musicRepository.addTrackToPlaylist(playlistId, trackId)
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        if (name.isBlank()) return
        scope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(playlistId, trackId)
            systemStorageManager.syncJsonSnapshotsIfEnabled()
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        scope.launch {
            musicRepository.removeTrackFromPlaylist(playlistId, trackId)
            systemStorageManager.syncJsonSnapshotsIfEnabled()
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
                systemStorageManager.syncJsonSnapshotsIfEnabled()
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
                systemStorageManager.syncJsonSnapshotsIfEnabled()
            }
        }
    }
}
