package com.auramusic.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.ArtistPlaylist
import com.auramusic.core.model.ArtistPlaylistBuilder
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.AudioEffectPreset
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.EqualizerPreset
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
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

    val deleteAnimationsEnabled: StateFlow<Boolean> =
        container.themePreferencesRepository.deleteAnimationsEnabled
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

    val playbackTuning: StateFlow<PlaybackTuning> = container.playbackPreferencesRepository.playbackTuning
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = PlaybackTuning(),
        )

    val audioEffects: StateFlow<AudioEffectsState> = container.audioEffectsPreferencesRepository.audioEffectsState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AudioEffectsState(),
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

    /**
     * Playlists generadas automáticamente agrupando las canciones por el nombre de su artista.
     */
    val artistPlaylists: StateFlow<List<ArtistPlaylist>> = tracks
        .map { currentTracks -> ArtistPlaylistBuilder.buildFromTracks(currentTracks) }
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

    private val _openPlayerChannel = Channel<Unit>(Channel.BUFFERED)
    val openPlayerEvents: Flow<Unit> = _openPlayerChannel.receiveAsFlow()

    private val _uiMessagesChannel = Channel<String>(Channel.BUFFERED)
    val uiMessages: Flow<String> = _uiMessagesChannel.receiveAsFlow()

    init {
        container.playerController.onUiAttached()

        viewModelScope.launch {
            val minSplashDelay = async { delay(320L) }
            val themeJob = async { container.themePreferencesRepository.accentColor.first() }
            val coverColorsJob = async { container.themePreferencesRepository.dynamicCoverColorsEnabled.first() }
            val deleteFxJob = async { container.themePreferencesRepository.deleteAnimationsEnabled.first() }
            val eqJob = async { container.equalizerPreferencesRepository.equalizerState.first() }
            val tuningJob = async { container.playbackPreferencesRepository.playbackTuning.first() }
            val fxJob = async { container.audioEffectsPreferencesRepository.audioEffectsState.first() }
            val tracksJob = async { container.musicRepository.observeAllTracks().first() }
            val playlistsJob = async { container.musicRepository.observePlaylists().first() }
            val rustJob = async {
                withContext(Dispatchers.Default) {
                    container.rustEngineInfo
                }
            }

            themeJob.await()
            coverColorsJob.await()
            deleteFxJob.await()
            val savedEq = eqJob.await()
            AuraRustBridge.setEqualizerConfig(savedEq.isEnabled, savedEq.bandGainsDb)
            val savedTuning = tuningJob.await()
            val savedFx = fxJob.await()
            syncEffectsToRust(savedFx)
            val resolvedParams = PlaybackTuningHelper.resolvePlaybackParameters(savedTuning, savedFx)
            container.playerController.setPlaybackParameters(resolvedParams.speed, resolvedParams.pitch)
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

    fun observeArtistPlaylist(artistKey: String): Flow<ArtistPlaylist?> {
        val targetKey = ArtistPlaylistBuilder.normalizeArtistKey(artistKey)
        return artistPlaylists.map { list -> list.firstOrNull { it.artistKey == targetKey } }
    }

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

    fun inspectDownloadUrl(url: String) {
        viewModelScope.launch {
            container.ytDlpAudioDownloader.inspectAudioUrl(rawUrl = url)
        }
    }

    fun confirmPendingDownload() {
        container.ytDlpAudioDownloader.startBackgroundConfirmedDownload(
            accentArgb = accentColor.value.primaryArgb.toInt(),
        )
    }

    fun downloadAudioFromUrl(url: String) {
        container.ytDlpAudioDownloader.startBackgroundUrlDownload(
            rawUrl = url,
            accentArgb = accentColor.value.primaryArgb.toInt(),
        )
    }

    fun cancelActiveDownload() {
        container.ytDlpAudioDownloader.requestCancelDownload()
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

    fun setDownloaderAudioQuality(quality: AudioDownloadQuality) {
        viewModelScope.launch {
            container.ytDlpAudioDownloader.setPreferredAudioQuality(quality)
        }
    }

    fun selectDownloaderPreviewFormat(formatId: String) {
        container.ytDlpAudioDownloader.selectPreviewAudioFormat(formatId)
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

    fun playShuffled(tracks: List<Track>) {
        container.playerController.playShuffled(tracks)
    }

    fun playNext(track: Track) {
        container.playerController.playNext(track)
    }

    fun addToQueue(track: Track) {
        container.playerController.addToQueue(track)
    }

    fun playQueueItemAt(index: Int) {
        container.playerController.playQueueItemAt(index)
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        container.playerController.moveQueueItem(fromIndex, toIndex)
    }

    fun removeFromQueue(index: Int) {
        container.playerController.removeFromQueue(index)
    }

    fun clearUpcomingQueue() {
        container.playerController.clearUpcomingQueue()
    }

    fun togglePlayPause() = container.playerController.togglePlayPause()

    fun seekTo(positionMs: Long) = container.playerController.seekTo(positionMs)

    fun skipNext() = container.playerController.skipToNext()

    fun skipPrevious() = container.playerController.skipToPrevious()

    fun toggleShuffle() = container.playerController.toggleShuffle()

    fun cycleRepeat() = container.playerController.cycleRepeatMode()

    fun setCrossfadeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.playbackPreferencesRepository.setCrossfadeEnabled(enabled)
        }
    }

    fun setCrossfadeSeconds(seconds: Int) {
        val clamped = PlaybackTuningHelper.clampCrossfadeSeconds(seconds)
        viewModelScope.launch {
            container.playbackPreferencesRepository.setCrossfadeSeconds(clamped)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = PlaybackTuningHelper.clampPlaybackSpeed(speed)
        if (audioEffects.value.slowedEnabled) {
            updateAudioEffectsState(
                audioEffects.value.copy(
                    slowedEnabled = false,
                    activePresetId = if (audioEffects.value.reverbEnabled) {
                        AudioEffectsState.PRESET_CUSTOM_ID
                    } else {
                        AudioEffectsState.PRESET_OFF_ID
                    },
                ),
            )
        }
        container.playerController.setPlaybackParameters(clamped, 1.0f)
        viewModelScope.launch {
            container.playbackPreferencesRepository.setPlaybackSpeed(clamped)
        }
    }

    fun requestOpenPlayer() {
        _openPlayerChannel.trySend(Unit)
    }

    /**
     * Abre e importa de forma segura una canción seleccionada desde otra app ("Abrir con")
     * y la reproduce al instante abriendo la pantalla del reproductor.
     */
    fun openExternalAudioUri(uri: Uri) {
        viewModelScope.launch {
            val importedTrack = container.audioImporter.importExternalAudioUri(
                uri = uri,
                accentArgb = accentColor.value.primaryArgb.toInt(),
            )
            if (importedTrack != null) {
                val currentTracks = container.musicRepository.observeAllTracks().first()
                val queue = if (currentTracks.any { it.id == importedTrack.id }) {
                    currentTracks
                } else {
                    listOf(importedTrack) + currentTracks
                }
                val startIndex = queue.indexOfFirst { it.id == importedTrack.id }.coerceAtLeast(0)
                container.playerController.playQueue(queue, startIndex)
                _openPlayerChannel.trySend(Unit)
                _uiMessagesChannel.trySend("Abriendo en Aura Music: ${importedTrack.title}")
            } else {
                _uiMessagesChannel.trySend("No se pudo abrir el archivo de audio seleccionado.")
            }
        }
    }

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

    fun setDeleteAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            container.themePreferencesRepository.setDeleteAnimationsEnabled(enabled)
        }
    }

    /**
     * Actualiza el nombre de la canción y el nombre del artista en Room.
     * Al emitirse en `tracks`, actualiza automáticamente Biblioteca, playlists de Artistas,
     * cola de reproducción activa y notificación del sistema.
     */
    fun updateTrackMetadata(trackId: Long, title: String, artist: String) {
        viewModelScope.launch {
            val updated = container.musicRepository.updateTrackMetadata(
                trackId = trackId,
                title = title,
                artist = artist,
            )
            if (updated != null) {
                _uiMessagesChannel.trySend("Metadatos actualizados: ${updated.title}")
            }
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

    fun selectAudioEffectPreset(preset: AudioEffectPreset) {
        updateAudioEffectsState(AudioEffectsState.fromPreset(preset))
    }

    fun setReverbEnabled(enabled: Boolean) {
        val current = audioEffects.value
        val nextPresetId = if (!enabled && !current.slowedEnabled && !current.spatial8dEnabled) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(reverbEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setReverbRoomSize(roomSize: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbRoomSize = roomSize,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setReverbDamping(damping: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbDamping = damping,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setReverbWetMix(wetMix: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbWetMix = wetMix,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setReverbWidth(width: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                reverbEnabled = true,
                reverbWidth = width,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSlowedEnabled(enabled: Boolean) {
        val current = audioEffects.value
        val nextPresetId = if (!enabled && !current.reverbEnabled && !current.spatial8dEnabled) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(slowedEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setSlowedSpeed(speed: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                slowedEnabled = true,
                slowedSpeed = speed,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSlowedPreservePitch(preservePitch: Boolean) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                slowedEnabled = true,
                slowedPreservePitch = preservePitch,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSlowedWarmth(warmth: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                slowedEnabled = true,
                slowedWarmth = warmth,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSpatial8dEnabled(enabled: Boolean) {
        val current = audioEffects.value
        val nextPresetId = if (!enabled && !current.reverbEnabled && !current.slowedEnabled) {
            AudioEffectsState.PRESET_OFF_ID
        } else {
            AudioEffectsState.PRESET_CUSTOM_ID
        }
        updateAudioEffectsState(current.copy(spatial8dEnabled = enabled, activePresetId = nextPresetId))
    }

    fun setSpatial8dSpeed(speed: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                spatial8dEnabled = true,
                spatial8dSpeed = speed,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun setSpatial8dDepth(depth: Float) {
        updateAudioEffectsState(
            audioEffects.value.copy(
                spatial8dEnabled = true,
                spatial8dDepth = depth,
                activePresetId = AudioEffectsState.PRESET_CUSTOM_ID,
            ),
        )
    }

    fun resetAudioEffects() {
        updateAudioEffectsState(AudioEffectsState())
    }

    private fun updateAudioEffectsState(nextState: AudioEffectsState) {
        val normalized = nextState.normalized()
        val resolvedParams = PlaybackTuningHelper.resolvePlaybackParameters(
            tuning = playbackTuning.value,
            effects = normalized,
        )
        container.playerController.setPlaybackParameters(resolvedParams.speed, resolvedParams.pitch)
        viewModelScope.launch {
            syncEffectsToRust(normalized)
            container.audioEffectsPreferencesRepository.saveAudioEffectsState(normalized)
        }
    }

    private suspend fun syncEffectsToRust(state: AudioEffectsState) {
        AuraRustBridge.setAudioEffectsConfig(
            reverbEnabled = state.reverbEnabled,
            reverbRoomSize = state.reverbRoomSize,
            reverbDamping = state.reverbDamping,
            reverbWetMix = state.reverbWetMix,
            reverbWidth = state.reverbWidth,
            slowedWarmthEnabled = state.isSlowedWarmthActive,
            slowedWarmthAmount = state.slowedWarmth,
            spatial8dEnabled = state.spatial8dEnabled,
            spatial8dSpeed = state.spatial8dSpeed,
            spatial8dDepth = state.spatial8dDepth,
        )
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
