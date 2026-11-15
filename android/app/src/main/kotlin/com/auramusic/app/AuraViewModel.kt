package com.auramusic.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.ArtistPlaylist
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.AudioEffectPreset
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.EqualizerPreset
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.SleepTimerState
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel coordinador principal de Aura Music.
 * Orquesta los tres coordinadores especializados ([AuraLibraryCoordinator],
 * [AuraPlaybackCoordinator] y [AuraAudioTuningCoordinator]) manteniendo la inicialización
 * paralela fuera del hilo principal y preservando una API limpia para Compose.
 */
class AuraViewModel(
    private val container: AuraAppContainer,
) : ViewModel() {

    private val _rustEngineInfo = MutableStateFlow("Cargando motor nativo Rust...")
    val rustEngineInfo: StateFlow<String> = _rustEngineInfo.asStateFlow()

    private val _isAppReady = MutableStateFlow(false)
    val isAppReady: StateFlow<Boolean> = _isAppReady.asStateFlow()

    private val _openPlayerChannel = Channel<Unit>(Channel.BUFFERED)
    val openPlayerEvents: Flow<Unit> = _openPlayerChannel.receiveAsFlow()

    private val _uiMessagesChannel = Channel<String>(Channel.BUFFERED)
    val uiMessages: Flow<String> = _uiMessagesChannel.receiveAsFlow()

    private lateinit var playbackCoordinator: AuraPlaybackCoordinator
    private lateinit var audioTuningCoordinator: AuraAudioTuningCoordinator
    private lateinit var libraryCoordinator: AuraLibraryCoordinator

    init {
        audioTuningCoordinator = AuraAudioTuningCoordinator(
            scope = viewModelScope,
            playerController = container.playerController,
            equalizerPreferencesRepository = container.equalizerPreferencesRepository,
            audioEffectsPreferencesRepository = container.audioEffectsPreferencesRepository,
            headphonePreferencesRepository = container.headphonePreferencesRepository,
            audioOutputMonitor = container.audioOutputMonitor,
            playbackTuningProvider = { playbackCoordinator.playbackTuning.value },
        )

        playbackCoordinator = AuraPlaybackCoordinator(
            scope = viewModelScope,
            playerController = container.playerController,
            playbackPreferencesRepository = container.playbackPreferencesRepository,
            audioEffectsStateProvider = { audioTuningCoordinator.audioEffects.value },
            onEffectsAdjustedByManualTuning = { updatedFx ->
                audioTuningCoordinator.updateAudioEffectsState(updatedFx)
            },
        )

        libraryCoordinator = AuraLibraryCoordinator(
            scope = viewModelScope,
            musicRepository = container.musicRepository,
            themePreferencesRepository = container.themePreferencesRepository,
            downloaderPreferencesRepository = container.downloaderPreferencesRepository,
            audioImporter = container.audioImporter,
            ytDlpAudioDownloader = container.ytDlpAudioDownloader,
            playerController = container.playerController,
            onOpenPlayerRequested = { _openPlayerChannel.trySend(Unit) },
            onUiMessage = { message -> _uiMessagesChannel.trySend(message) },
        )

        container.playerController.onUiAttached()

        viewModelScope.launch {
            val minSplashDelay = async { delay(320L) }
            val themeJob = async { container.themePreferencesRepository.accentColor.first() }
            val coverColorsJob = async { container.themePreferencesRepository.dynamicCoverColorsEnabled.first() }
            val deleteFxJob = async { container.themePreferencesRepository.deleteAnimationsEnabled.first() }
            val eqJob = async { container.equalizerPreferencesRepository.equalizerState.first() }
            val tuningJob = async { container.playbackPreferencesRepository.playbackTuning.first() }
            val fxJob = async { container.audioEffectsPreferencesRepository.audioEffectsState.first() }
            val hpJob = async { container.headphonePreferencesRepository.headphonePreferences.first() }
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
            val savedHp = hpJob.await()
            container.playerController.updateHeadphoneControlTuning(
                previousRestartThresholdMs = savedHp.previousRestartThresholdMs,
                wrapQueueNavigation = savedHp.wrapQueueNavigation,
            )
            audioTuningCoordinator.syncEffectsToRust(savedFx, audioOutputRoute.value, savedHp)
            val resolvedParams = PlaybackTuningHelper.resolvePlaybackParameters(savedTuning, savedFx)
            container.playerController.setPlaybackParameters(resolvedParams.speed, resolvedParams.pitch)
            tracksJob.await()
            playlistsJob.await()
            _rustEngineInfo.value = rustJob.await()
            minSplashDelay.await()
            _isAppReady.value = true
        }

        audioTuningCoordinator.startSyncLoops()
        libraryCoordinator.refreshDownloaderStatusInBackground()
    }

    val accentColor: StateFlow<AppAccentColor>
        get() = libraryCoordinator.accentColor

    val dynamicCoverColorsEnabled: StateFlow<Boolean>
        get() = libraryCoordinator.dynamicCoverColorsEnabled

    val deleteAnimationsEnabled: StateFlow<Boolean>
        get() = libraryCoordinator.deleteAnimationsEnabled

    val equalizerState: StateFlow<EqualizerState>
        get() = audioTuningCoordinator.equalizerState

    val playbackTuning: StateFlow<PlaybackTuning>
        get() = playbackCoordinator.playbackTuning

    val audioEffects: StateFlow<AudioEffectsState>
        get() = audioTuningCoordinator.audioEffects

    val headphonePreferences: StateFlow<HeadphonePreferences>
        get() = audioTuningCoordinator.headphonePreferences

    val audioOutputRoute: StateFlow<AudioOutputRouteState>
        get() = audioTuningCoordinator.audioOutputRoute

    val tracks: StateFlow<List<Track>>
        get() = libraryCoordinator.tracks

    val likedTracks: StateFlow<List<Track>>
        get() = libraryCoordinator.likedTracks

    val likedCount: StateFlow<Int>
        get() = libraryCoordinator.likedCount

    val playlists: StateFlow<List<Playlist>>
        get() = libraryCoordinator.playlists

    /**
     * Playlists generadas automáticamente agrupando las canciones por el nombre de su artista.
     */
    val artistPlaylists: StateFlow<List<ArtistPlaylist>>
        get() = libraryCoordinator.artistPlaylists

    val playbackState: StateFlow<PlaybackState>
        get() = playbackCoordinator.playbackState

    val sleepTimerState: StateFlow<SleepTimerState>
        get() = playbackCoordinator.sleepTimerState

    val importProgress: StateFlow<ImportProgress>
        get() = libraryCoordinator.importProgress

    val downloadState: StateFlow<DownloadState>
        get() = libraryCoordinator.downloadState

    val downloaderEngineStatus: StateFlow<DownloaderEngineStatus>
        get() = libraryCoordinator.downloaderEngineStatus

    fun observePlaylistName(playlistId: Long): Flow<String?> =
        libraryCoordinator.observePlaylistName(playlistId)

    fun observeTracksForPlaylist(playlistId: Long): Flow<List<Track>> =
        libraryCoordinator.observeTracksForPlaylist(playlistId)

    fun observeArtistPlaylist(artistKey: String): Flow<ArtistPlaylist?> =
        libraryCoordinator.observeArtistPlaylist(artistKey)

    fun importFiles(uris: List<Uri>) = libraryCoordinator.importFiles(uris)

    fun importFolder(treeUri: Uri) = libraryCoordinator.importFolder(treeUri)

    fun dismissImportMessage() = libraryCoordinator.dismissImportMessage()

    fun inspectDownloadUrl(url: String) = libraryCoordinator.inspectDownloadUrl(url)

    fun confirmPendingDownload() = libraryCoordinator.confirmPendingDownload()

    fun downloadAudioFromUrl(url: String) = libraryCoordinator.downloadAudioFromUrl(url)

    fun cancelActiveDownload() = libraryCoordinator.cancelActiveDownload()

    fun checkDownloaderUpdates() = libraryCoordinator.checkDownloaderUpdates()

    fun setDownloaderAutoUpdate(enabled: Boolean) =
        libraryCoordinator.setDownloaderAutoUpdate(enabled)

    fun setDownloaderAudioQuality(quality: AudioDownloadQuality) =
        libraryCoordinator.setDownloaderAudioQuality(quality)

    fun selectDownloaderPreviewFormat(formatId: String) =
        libraryCoordinator.selectDownloaderPreviewFormat(formatId)

    fun dismissDownloadStatus() = libraryCoordinator.dismissDownloadStatus()

    fun dismissDownloaderUpdateMessage() = libraryCoordinator.dismissDownloaderUpdateMessage()

    fun playQueue(tracks: List<Track>, startIndex: Int) =
        playbackCoordinator.playQueue(tracks, startIndex)

    fun playShuffled(tracks: List<Track>) = playbackCoordinator.playShuffled(tracks)

    fun playNext(track: Track) = playbackCoordinator.playNext(track)

    fun addToQueue(track: Track) = playbackCoordinator.addToQueue(track)

    fun playQueueItemAt(index: Int) = playbackCoordinator.playQueueItemAt(index)

    fun moveQueueItem(fromIndex: Int, toIndex: Int) =
        playbackCoordinator.moveQueueItem(fromIndex, toIndex)

    fun removeFromQueue(index: Int) = playbackCoordinator.removeFromQueue(index)

    fun clearUpcomingQueue() = playbackCoordinator.clearUpcomingQueue()

    fun togglePlayPause() = playbackCoordinator.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackCoordinator.seekTo(positionMs)

    fun skipNext() = playbackCoordinator.skipNext()

    fun skipPrevious() = playbackCoordinator.skipPrevious()

    fun toggleShuffle() = playbackCoordinator.toggleShuffle()

    fun cycleRepeat() = playbackCoordinator.cycleRepeat()

    fun setCrossfadeEnabled(enabled: Boolean) = playbackCoordinator.setCrossfadeEnabled(enabled)

    fun setCrossfadeSeconds(seconds: Int) = playbackCoordinator.setCrossfadeSeconds(seconds)

    fun setPlaybackSpeed(speed: Float) = playbackCoordinator.setPlaybackSpeed(speed)

    fun setPlaybackPitch(pitch: Float) = playbackCoordinator.setPlaybackPitch(pitch)

    fun setLinkPitchToSpeed(linked: Boolean) = playbackCoordinator.setLinkPitchToSpeed(linked)

    fun resetPlaybackSpeedAndPitch() = playbackCoordinator.resetPlaybackSpeedAndPitch()

    fun startSleepTimerMinutes(minutes: Int, fadeOutOnFinish: Boolean) =
        playbackCoordinator.startSleepTimerMinutes(minutes, fadeOutOnFinish)

    fun setSleepTimerEndOfTrack(fadeOutOnFinish: Boolean) =
        playbackCoordinator.setSleepTimerEndOfTrack(fadeOutOnFinish)

    fun setSleepTimerEndOfQueue(fadeOutOnFinish: Boolean) =
        playbackCoordinator.setSleepTimerEndOfQueue(fadeOutOnFinish)

    fun addSleepTimerMinutes(deltaMinutes: Int) =
        playbackCoordinator.addSleepTimerMinutes(deltaMinutes)

    fun cancelSleepTimer() = playbackCoordinator.cancelSleepTimer()

    fun requestOpenPlayer() {
        _openPlayerChannel.trySend(Unit)
    }

    /**
     * Abre e importa de forma segura una canción seleccionada desde otra app ("Abrir con")
     * y la reproduce al instante abriendo la pantalla del reproductor.
     */
    fun openExternalAudioUri(uri: Uri) = libraryCoordinator.openExternalAudioUri(uri)

    fun toggleLike(trackId: Long) = libraryCoordinator.toggleLike(trackId)

    fun deleteTrack(trackId: Long) = libraryCoordinator.deleteTrack(trackId)

    fun createPlaylist(name: String) = libraryCoordinator.createPlaylist(name)

    fun deletePlaylist(playlistId: Long) = libraryCoordinator.deletePlaylist(playlistId)

    fun addTrackToPlaylist(playlistId: Long, trackId: Long) =
        libraryCoordinator.addTrackToPlaylist(playlistId, trackId)

    fun createPlaylistAndAddTrack(name: String, trackId: Long) =
        libraryCoordinator.createPlaylistAndAddTrack(name, trackId)

    fun removeTrackFromPlaylist(playlistId: Long, trackId: Long) =
        libraryCoordinator.removeTrackFromPlaylist(playlistId, trackId)

    fun selectAccentColor(color: AppAccentColor) = libraryCoordinator.selectAccentColor(color)

    fun setDynamicCoverColorsEnabled(enabled: Boolean) =
        libraryCoordinator.setDynamicCoverColorsEnabled(enabled)

    fun setDeleteAnimationsEnabled(enabled: Boolean) =
        libraryCoordinator.setDeleteAnimationsEnabled(enabled)

    /**
     * Actualiza el nombre de la canción y el nombre del artista en Room.
     */
    fun updateTrackMetadata(trackId: Long, title: String, artist: String) =
        libraryCoordinator.updateTrackMetadata(trackId, title, artist)

    /**
     * Convierte una imagen de galería a WebP sin pérdida en segundo plano y la asigna a la pista.
     */
    fun setCustomTrackCover(trackId: Long, imageUri: Uri) =
        libraryCoordinator.setCustomTrackCover(trackId, imageUri)

    fun setEqualizerEnabled(enabled: Boolean) = audioTuningCoordinator.setEqualizerEnabled(enabled)

    fun setEqualizerBandGain(bandIndex: Int, gainDb: Float) =
        audioTuningCoordinator.setEqualizerBandGain(bandIndex, gainDb)

    fun selectEqualizerPreset(preset: EqualizerPreset) =
        audioTuningCoordinator.selectEqualizerPreset(preset)

    fun resetEqualizer() = audioTuningCoordinator.resetEqualizer()

    fun selectAudioEffectPreset(preset: AudioEffectPreset) =
        audioTuningCoordinator.selectAudioEffectPreset(preset)

    fun setReverbEnabled(enabled: Boolean) = audioTuningCoordinator.setReverbEnabled(enabled)

    fun setReverbRoomSize(roomSize: Float) = audioTuningCoordinator.setReverbRoomSize(roomSize)

    fun setReverbDamping(damping: Float) = audioTuningCoordinator.setReverbDamping(damping)

    fun setReverbWetMix(wetMix: Float) = audioTuningCoordinator.setReverbWetMix(wetMix)

    fun setReverbWidth(width: Float) = audioTuningCoordinator.setReverbWidth(width)

    fun setSlowedEnabled(enabled: Boolean) = audioTuningCoordinator.setSlowedEnabled(enabled)

    fun setSlowedSpeed(speed: Float) = audioTuningCoordinator.setSlowedSpeed(speed)

    fun setSlowedPreservePitch(preservePitch: Boolean) =
        audioTuningCoordinator.setSlowedPreservePitch(preservePitch)

    fun setSlowedWarmth(warmth: Float) = audioTuningCoordinator.setSlowedWarmth(warmth)

    fun setSpatial8dEnabled(enabled: Boolean) = audioTuningCoordinator.setSpatial8dEnabled(enabled)

    fun setSpatial8dSpeed(speed: Float) = audioTuningCoordinator.setSpatial8dSpeed(speed)

    fun setSpatial8dDepth(depth: Float) = audioTuningCoordinator.setSpatial8dDepth(depth)

    fun setCrossfeedEnabled(enabled: Boolean) = audioTuningCoordinator.setCrossfeedEnabled(enabled)

    fun setCrossfeedStrength(strength: Float) =
        audioTuningCoordinator.setCrossfeedStrength(strength)

    fun setCrossfeedAngle(angle: Float) = audioTuningCoordinator.setCrossfeedAngle(angle)

    fun resetAudioEffects() = audioTuningCoordinator.resetAudioEffects()

    fun setAutoPauseOnDisconnect(enabled: Boolean) =
        audioTuningCoordinator.setAutoPauseOnDisconnect(enabled)

    fun setAutoResumeOnConnect(enabled: Boolean) =
        audioTuningCoordinator.setAutoResumeOnConnect(enabled)

    fun setAutoDisable8dOnSpeaker(enabled: Boolean) =
        audioTuningCoordinator.setAutoDisable8dOnSpeaker(enabled)

    fun setAutoEnableCrossfeedOnHeadphones(enabled: Boolean) =
        audioTuningCoordinator.setAutoEnableCrossfeedOnHeadphones(enabled)

    fun setDoubleTapNextEnabled(enabled: Boolean) =
        audioTuningCoordinator.setDoubleTapNextEnabled(enabled)

    fun setTripleTapPreviousEnabled(enabled: Boolean) =
        audioTuningCoordinator.setTripleTapPreviousEnabled(enabled)

    fun setPreviousRestartThresholdMs(ms: Long) =
        audioTuningCoordinator.setPreviousRestartThresholdMs(ms)

    fun setWrapQueueNavigation(enabled: Boolean) =
        audioTuningCoordinator.setWrapQueueNavigation(enabled)

    fun setSeekStepSeconds(seconds: Int) = audioTuningCoordinator.setSeekStepSeconds(seconds)

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
