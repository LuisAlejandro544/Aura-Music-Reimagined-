package com.auramusic.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.EqualizerPreset
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel coordinador principal de Aura Music.
 * Conecta de forma reactiva los flujos de Room, DataStore, AudioImporter y ExoPlayer,
 * manteniendo todas las operaciones pesadas en coroutines fuera del hilo principal.
 */
class AuraViewModel(
    private val container: AuraAppContainer,
) : ViewModel() {

    val accentColor: StateFlow<AppAccentColor> = container.themePreferencesRepository.accentColor
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppAccentColor.AMETHYST,
        )

    val dynamicCoverColorsEnabled: StateFlow<Boolean> =
        container.themePreferencesRepository.dynamicCoverColorsEnabled
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = true,
            )

    val equalizerState: StateFlow<EqualizerState> = container.equalizerPreferencesRepository.equalizerState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = EqualizerState(),
        )

    val tracks: StateFlow<List<Track>> = container.musicRepository.observeAllTracks()
        .onEach { updated -> container.playerController.refreshTrackSnapshots(updated) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val likedTracks: StateFlow<List<Track>> = container.musicRepository.observeLikedTracks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val likedCount: StateFlow<Int> = container.musicRepository.observeLikedCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0,
        )

    val playlists: StateFlow<List<Playlist>> = container.musicRepository.observePlaylists()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    val playbackState: StateFlow<PlaybackState> = container.playerController.playbackState

    val importProgress: StateFlow<ImportProgress> = container.audioImporter.progress

    val downloadState: StateFlow<DownloadState> = container.ytDlpAudioDownloader.downloadState

    val downloaderEngineStatus: StateFlow<DownloaderEngineStatus> =
        container.ytDlpAudioDownloader.engineStatus

    private val _rustEngineInfo = MutableStateFlow("Cargando motor nativo Rust...")
    val rustEngineInfo: StateFlow<String> = _rustEngineInfo.asStateFlow()

    private val _isAppReady = MutableStateFlow(false)
    val isAppReady: StateFlow<Boolean> = _isAppReady.asStateFlow()

    init {
        container.playerController.onUiAttached()

        viewModelScope.launch {
            val minSplashDelay = async { delay(320L) }
            val themeJob = async { container.themePreferencesRepository.accentColor.first() }
            val coverColorsJob = async { container.themePreferencesRepository.dynamicCoverColorsEnabled.first() }
            val eqJob = async { container.equalizerPreferencesRepository.equalizerState.first() }
            val tracksJob = async { container.musicRepository.observeAllTracks().first() }
            val playlistsJob = async { container.musicRepository.observePlaylists().first() }
            val rustJob = async {
                withContext(Dispatchers.Default) {
                    container.rustEngineInfo
                }
            }

            themeJob.await()
            coverColorsJob.await()
            val savedEq = eqJob.await()
            AuraRustBridge.setEqualizerConfig(savedEq.isEnabled, savedEq.bandGainsDb)
            tracksJob.await()
            playlistsJob.await()
            _rustEngineInfo.value = rustJob.await()
            minSplashDelay.await()
            _isAppReady.value = true
        }

        // Inicializa y consulta versiones de Python/yt-dlp/QuickJS-NG en segundo plano sin retrasar el arranque.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.ytDlpAudioDownloader.refreshEngineStatus() }
        }
    }

    fun observePlaylistName(playlistId: Long): Flow<String?> =
        container.musicRepository.observePlaylistName(playlistId)

    fun observeTracksForPlaylist(playlistId: Long): Flow<List<Track>> =
        container.musicRepository.observeTracksForPlaylist(playlistId)

    fun importFiles(uris: List<Uri>) {
        viewModelScope.launch {
            container.audioImporter.importDocumentUris(
                uris = uris,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            container.audioImporter.importFolderTree(
                treeUri = treeUri,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun dismissImportMessage() {
        container.audioImporter.clearLastMessage()
    }

    fun downloadAudioFromUrl(url: String) {
        viewModelScope.launch {
            container.ytDlpAudioDownloader.downloadAudio(
                rawUrl = url,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
        }
    }

    fun checkDownloaderUpdates() {
        viewModelScope.launch {
            container.ytDlpAudioDownloader.checkAndUpdateEngines(force = true)
        }
    }

    fun setDownloaderAutoUpdate(enabled: Boolean) {
        viewModelScope.launch {
            container.downloaderPreferencesRepository.setAutoUpdateEnabled(enabled)
            container.ytDlpAudioDownloader.refreshEngineStatus()
        }
    }

    fun dismissDownloadStatus() {
        container.ytDlpAudioDownloader.clearDownloadStatus()
    }

    fun dismissDownloaderUpdateMessage() {
        container.ytDlpAudioDownloader.clearUpdateMessage()
    }

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        container.playerController.playQueue(tracks, startIndex)
    }

    fun togglePlayPause() = container.playerController.togglePlayPause()

    fun seekTo(positionMs: Long) = container.playerController.seekTo(positionMs)

    fun skipNext() = container.playerController.skipToNext()

    fun skipPrevious() = container.playerController.skipToPrevious()

    fun toggleShuffle() = container.playerController.toggleShuffle()

    fun cycleRepeat() = container.playerController.cycleRepeatMode()

    fun toggleLike(trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.toggleLike(trackId)
        }
    }

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch {
            val snapshot = tracks.value
            val targetTrack = snapshot.firstOrNull { it.id == trackId }
            container.musicRepository.deleteTrack(trackId)
            container.audioImporter.cleanupOrphanCovers()
            if (targetTrack != null && snapshot.none { it.id != trackId && it.contentUri == targetTrack.contentUri }) {
                container.audioImporter.deleteOwnedDownloadFileIfExists(targetTrack.contentUri)
            }
        }
    }

    fun createPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            container.musicRepository.createPlaylist(name)
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            container.musicRepository.deletePlaylist(playlistId)
        }
    }

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val playlistId = container.musicRepository.createPlaylist(name)
            container.musicRepository.addTrackToPlaylist(playlistId, trackId)
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) {
        viewModelScope.launch {
            container.musicRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun selectAccentColor(color: AppAccentColor) {
        viewModelScope.launch {
            container.themePreferencesRepository.setAccentColor(color)
        }
    }

    fun setDynamicCoverColorsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.themePreferencesRepository.setDynamicCoverColorsEnabled(enabled)
        }
    }

    /**
     * Convierte una imagen de galería a WebP sin pérdida en segundo plano y la asigna a la pista.
     */
    fun setCustomTrackCover(trackId: Long, imageUri: Uri) {
        viewModelScope.launch {
            val webpPath = container.audioImporter.saveCustomCoverAsLosslessWebp(
                trackId = trackId,
                imageUri = imageUri,
            )
            if (webpPath != null) {
                container.musicRepository.updateTrackCover(
                    trackId = trackId,
                    newCoverPath = webpPath,
                    isProcedural = false,
                )
            }
        }
    }

    fun setEqualizerEnabled(enabled: Boolean) {
        updateEqualizerState(equalizerState.value.copy(isEnabled = enabled))
    }

    fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0 until EqualizerState.BAND_COUNT) return
        val updatedBands = equalizerState.value.bandGainsDb.toMutableList()
        while (updatedBands.size < EqualizerState.BAND_COUNT) updatedBands.add(0f)
        updatedBands[bandIndex] = gainDb.coerceIn(
            EqualizerState.MIN_GAIN_DB,
            EqualizerState.MAX_GAIN_DB,
        )
        updateEqualizerState(
            equalizerState.value.copy(
                bandGainsDb = updatedBands,
                activePresetId = EqualizerState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun selectEqualizerPreset(preset: EqualizerPreset) {
        updateEqualizerState(
            equalizerState.value.copy(
                isEnabled = true,
                bandGainsDb = preset.gainsDb,
                activePresetId = preset.id,
            ),
        )
    }

    fun resetEqualizer() {
        updateEqualizerState(
            equalizerState.value.copy(
                bandGainsDb = List(EqualizerState.BAND_COUNT) { 0f },
                activePresetId = EqualizerState.PRESET_FLAT_ID,
            ),
        )
    }

    private fun updateEqualizerState(nextState: EqualizerState) {
        val normalized = nextState.normalized()
        viewModelScope.launch {
            AuraRustBridge.setEqualizerConfig(normalized.isEnabled, normalized.bandGainsDb)
            container.equalizerPreferencesRepository.saveEqualizerState(normalized)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // No liberamos el MediaController global aquí para que, si el usuario vuelve
        // después de cerrar la app de recientes, los controles sigan enlazados al servicio.
        container.playerController.onUiDetached()
    }

    class Factory(
        private val container: AuraAppContainer,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuraViewModel(container) as T
        }
    }
}
