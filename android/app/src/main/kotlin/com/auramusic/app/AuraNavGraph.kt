package com.auramusic.app

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.ArtistPlaylist
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.SleepTimerState
import com.auramusic.core.model.SystemFolderStorageState
import com.auramusic.core.model.Track
import com.auramusic.feature.downloader.DownloaderScreen
import com.auramusic.feature.effects.AudioEffectsScreen
import com.auramusic.feature.equalizer.EqualizerScreen
import com.auramusic.feature.library.LibraryScreen
import com.auramusic.feature.player.PlayerScreen
import com.auramusic.feature.player.QueueScreen
import com.auramusic.feature.playlists.ArtistDetailScreen
import com.auramusic.feature.playlists.LikedSongsScreen
import com.auramusic.feature.playlists.PlaylistDetailScreen
import com.auramusic.feature.playlists.PlaylistsScreen
import com.auramusic.feature.settings.SettingsAccentThemeScreen
import com.auramusic.feature.settings.SettingsHeadphonesScreen
import com.auramusic.feature.settings.SettingsMediaButtonsScreen
import com.auramusic.feature.settings.SettingsScreen
import com.auramusic.feature.settings.SettingsSystemFolderScreen
import com.auramusic.feature.settings.SettingsVisualEffectsScreen

/**
 * Subgrafo de navegación para Biblioteca, Playlists, Favoritos, Artistas y Descargador.
 * Mantiene `AuraAppRoot` limpio y enfocado únicamente en la orquestación global.
 */
internal fun NavGraphBuilder.auraLibraryAndPlaylistsGraph(
    navController: NavHostController,
    viewModel: AuraViewModel,
    tracks: List<Track>,
    likedTracks: List<Track>,
    likedCount: Int,
    playlists: List<Playlist>,
    artistPlaylists: List<ArtistPlaylist>,
    playbackState: PlaybackState,
    importProgress: ImportProgress,
    downloadState: DownloadState,
    downloaderEngineStatus: DownloaderEngineStatus,
    accentColor: AppAccentColor,
    deleteAnimationsEnabled: Boolean,
    deleteEffectRenderer: DeleteEffectFrameRenderer,
    safLaunchers: AuraSafLaunchers,
    onShowQueueFeedback: (String) -> Unit,
    onRequestAddToPlaylist: (Track) -> Unit,
    onRequestEditMetadata: (Track) -> Unit,
) {
    composable(AuraRoutes.LIBRARY) {
        LibraryScreen(
            tracks = tracks,
            likedCount = likedCount,
            currentPlayingTrackId = playbackState.currentTrack?.id,
            importProgress = importProgress,
            onImportSingleTrack = safLaunchers.pickSingleAudio,
            onImportMultipleTracks = safLaunchers.pickMultipleAudio,
            onImportFolder = safLaunchers.pickFolderTree,
            onPlayTrack = viewModel::playQueue,
            onToggleLike = viewModel::toggleLike,
            onPlayNext = { track ->
                viewModel.playNext(track)
                onShowQueueFeedback("Se reproducirá a continuación: ${track.title}")
            },
            onAddToQueue = { track ->
                viewModel.addToQueue(track)
                onShowQueueFeedback("Añadida a la cola: ${track.title}")
            },
            onAddToPlaylist = onRequestAddToPlaylist,
            onEditMetadata = onRequestEditMetadata,
            onChangeCover = { track -> safLaunchers.pickCustomCoverForTrack(track.id) },
            onDeleteTrack = viewModel::deleteTrack,
            onOpenLikedSongs = { navController.navigate(AuraRoutes.LIKED) },
            onDismissImportMessage = viewModel::dismissImportMessage,
            onOpenDownloader = {
                navController.navigate(AuraRoutes.DOWNLOADER) {
                    launchSingleTop = true
                }
            },
            deleteAnimationsEnabled = deleteAnimationsEnabled,
            accentArgb = accentColor.primaryArgb.toInt(),
            deleteEffectRenderer = deleteEffectRenderer,
        )
    }

    composable(AuraRoutes.PLAYLISTS) {
        PlaylistsScreen(
            playlists = playlists,
            likedCount = likedCount,
            artistPlaylists = artistPlaylists,
            onOpenLikedSongs = { navController.navigate(AuraRoutes.LIKED) },
            onOpenPlaylist = { playlistId ->
                navController.navigate(AuraRoutes.playlistDetail(playlistId))
            },
            onOpenArtist = { artistKey ->
                navController.navigate(AuraRoutes.artistDetail(artistKey))
            },
            onPlayArtist = { artistPlaylist ->
                viewModel.playQueue(artistPlaylist.tracks, 0)
            },
            onCreatePlaylist = viewModel::createPlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
        )
    }

    composable(AuraRoutes.LIKED) {
        LikedSongsScreen(
            tracks = likedTracks,
            currentPlayingTrackId = playbackState.currentTrack?.id,
            onBack = { navController.popBackStack() },
            onPlayTrack = viewModel::playQueue,
            onToggleLike = viewModel::toggleLike,
            onPlayNext = { track ->
                viewModel.playNext(track)
                onShowQueueFeedback("Se reproducirá a continuación: ${track.title}")
            },
            onAddToQueue = { track ->
                viewModel.addToQueue(track)
                onShowQueueFeedback("Añadida a la cola: ${track.title}")
            },
            onAddToPlaylist = onRequestAddToPlaylist,
            onEditMetadata = onRequestEditMetadata,
        )
    }

    composable(
        route = AuraRoutes.PLAYLIST_DETAIL,
        arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
    ) { entry ->
        val playlistId = entry.arguments?.getLong("playlistId") ?: 0L
        val playlistName by viewModel.observePlaylistName(playlistId)
            .collectAsStateWithLifecycle(initialValue = "Playlist")
        val playlistTracks by viewModel.observeTracksForPlaylist(playlistId)
            .collectAsStateWithLifecycle(initialValue = emptyList())

        PlaylistDetailScreen(
            playlistName = playlistName ?: "Playlist",
            tracks = playlistTracks,
            currentPlayingTrackId = playbackState.currentTrack?.id,
            onBack = { navController.popBackStack() },
            onPlayTrack = viewModel::playQueue,
            onToggleLike = viewModel::toggleLike,
            onRemoveFromPlaylist = { trackId ->
                viewModel.removeTrackFromPlaylist(playlistId, trackId)
            },
            onPlayNext = { track ->
                viewModel.playNext(track)
                onShowQueueFeedback("Se reproducirá a continuación: ${track.title}")
            },
            onAddToQueue = { track ->
                viewModel.addToQueue(track)
                onShowQueueFeedback("Añadida a la cola: ${track.title}")
            },
            onEditMetadata = onRequestEditMetadata,
            deleteAnimationsEnabled = deleteAnimationsEnabled,
            accentArgb = accentColor.primaryArgb.toInt(),
            deleteEffectRenderer = deleteEffectRenderer,
        )
    }

    composable(
        route = AuraRoutes.ARTIST_DETAIL,
        arguments = listOf(navArgument("artistKey") { type = NavType.StringType }),
    ) { entry ->
        val encodedKey = entry.arguments?.getString("artistKey").orEmpty()
        val artistKey = Uri.decode(encodedKey)
        val artistPlaylist by viewModel.observeArtistPlaylist(artistKey)
            .collectAsStateWithLifecycle(initialValue = null)

        ArtistDetailScreen(
            artistPlaylist = artistPlaylist,
            currentPlayingTrackId = playbackState.currentTrack?.id,
            onBack = { navController.popBackStack() },
            onPlayTrack = viewModel::playQueue,
            onPlayShuffled = viewModel::playShuffled,
            onToggleLike = viewModel::toggleLike,
            onPlayNext = { track ->
                viewModel.playNext(track)
                onShowQueueFeedback("Se reproducirá a continuación: ${track.title}")
            },
            onAddToQueue = { track ->
                viewModel.addToQueue(track)
                onShowQueueFeedback("Añadida a la cola: ${track.title}")
            },
            onAddToPlaylist = onRequestAddToPlaylist,
            onEditMetadata = onRequestEditMetadata,
        )
    }

    composable(AuraRoutes.DOWNLOADER) {
        DownloaderScreen(
            downloadState = downloadState,
            engineStatus = downloaderEngineStatus,
            onBack = { navController.popBackStack() },
            onInspectUrl = viewModel::inspectDownloadUrl,
            onConfirmDownload = viewModel::confirmPendingDownload,
            onCancelPreview = viewModel::dismissDownloadStatus,
            onCancelDownload = viewModel::cancelActiveDownload,
            onPlayDownloadedTrack = { track ->
                val idx = tracks.indexOfFirst {
                    it.contentUri == track.contentUri ||
                        it.trackFingerprint == track.trackFingerprint
                }
                if (idx >= 0) {
                    viewModel.playQueue(tracks, idx)
                } else {
                    viewModel.playQueue(listOf(track) + tracks, 0)
                }
                navController.navigate(AuraRoutes.PLAYER) {
                    launchSingleTop = true
                }
            },
            onCheckUpdates = viewModel::checkDownloaderUpdates,
            onToggleAutoUpdate = viewModel::setDownloaderAutoUpdate,
            onSelectQualityPreset = viewModel::setDownloaderAudioQuality,
            onSelectPreviewFormat = viewModel::selectDownloaderPreviewFormat,
            onDismissDownloadStatus = viewModel::dismissDownloadStatus,
            onDismissUpdateMessage = viewModel::dismissDownloaderUpdateMessage,
            onScreenActiveChanged = viewModel::setDownloaderScreenActive,
        )
    }
}

/**
 * Subgrafo de navegación para Ajustes y sus subpantallas dedicadas
 * (tema visual, animaciones Rust, auriculares, botones multimedia y carpeta Aura Music).
 */
internal fun NavGraphBuilder.auraSettingsGraph(
    navController: NavHostController,
    viewModel: AuraViewModel,
    accentColor: AppAccentColor,
    dynamicCoverColorsEnabled: Boolean,
    deleteAnimationsEnabled: Boolean,
    rustEngineInfo: String,
    audioOutputRoute: AudioOutputRouteState,
    headphonePreferences: HeadphonePreferences,
    systemFolderState: SystemFolderStorageState,
    requestSystemStoragePermission: () -> Unit,
) {
    composable(AuraRoutes.SETTINGS) {
        SettingsScreen(
            selectedAccent = accentColor,
            dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
            deleteAnimationsEnabled = deleteAnimationsEnabled,
            rustEngineInfo = rustEngineInfo,
            outputRoute = audioOutputRoute,
            headphonePreferences = headphonePreferences,
            systemFolderState = systemFolderState,
            onOpenAccentTheme = {
                navController.navigate(AuraRoutes.SETTINGS_THEME) {
                    launchSingleTop = true
                }
            },
            onOpenVisualEffects = {
                navController.navigate(AuraRoutes.SETTINGS_VISUAL_FX) {
                    launchSingleTop = true
                }
            },
            onOpenHeadphoneSettings = {
                navController.navigate(AuraRoutes.SETTINGS_HEADPHONES) {
                    launchSingleTop = true
                }
            },
            onOpenMediaButtons = {
                navController.navigate(AuraRoutes.SETTINGS_MEDIA_BUTTONS) {
                    launchSingleTop = true
                }
            },
            onOpenSystemFolder = {
                navController.navigate(AuraRoutes.SETTINGS_SYSTEM_FOLDER) {
                    launchSingleTop = true
                }
            },
        )
    }

    composable(AuraRoutes.SETTINGS_THEME) {
        SettingsAccentThemeScreen(
            selectedAccent = accentColor,
            onSelectAccent = viewModel::selectAccentColor,
            onBack = { navController.popBackStack() },
        )
    }

    composable(AuraRoutes.SETTINGS_VISUAL_FX) {
        SettingsVisualEffectsScreen(
            dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
            deleteAnimationsEnabled = deleteAnimationsEnabled,
            onToggleDynamicCoverColors = viewModel::setDynamicCoverColorsEnabled,
            onToggleDeleteAnimations = viewModel::setDeleteAnimationsEnabled,
            onBack = { navController.popBackStack() },
        )
    }

    composable(AuraRoutes.SETTINGS_HEADPHONES) {
        SettingsHeadphonesScreen(
            outputRoute = audioOutputRoute,
            headphonePreferences = headphonePreferences,
            onSetAutoPauseOnDisconnect = viewModel::setAutoPauseOnDisconnect,
            onSetAutoResumeOnConnect = viewModel::setAutoResumeOnConnect,
            onSetAutoDisable8dOnSpeaker = viewModel::setAutoDisable8dOnSpeaker,
            onSetAutoEnableCrossfeedOnHeadphones = viewModel::setAutoEnableCrossfeedOnHeadphones,
            onBack = { navController.popBackStack() },
        )
    }

    composable(AuraRoutes.SETTINGS_MEDIA_BUTTONS) {
        SettingsMediaButtonsScreen(
            headphonePreferences = headphonePreferences,
            onSetDoubleTapNext = viewModel::setDoubleTapNextEnabled,
            onSetTripleTapPrevious = viewModel::setTripleTapPreviousEnabled,
            onSetWrapQueueNavigation = viewModel::setWrapQueueNavigation,
            onSetPreviousRestartThresholdMs = viewModel::setPreviousRestartThresholdMs,
            onSetSeekStepSeconds = viewModel::setSeekStepSeconds,
            onBack = { navController.popBackStack() },
        )
    }

    composable(AuraRoutes.SETTINGS_SYSTEM_FOLDER) {
        SettingsSystemFolderScreen(
            state = systemFolderState,
            onToggleEnabled = { enabled ->
                viewModel.setSystemFolderEnabled(
                    enabled = enabled,
                    requestPermission = requestSystemStoragePermission,
                )
            },
            onRequestPermission = {
                viewModel.setSystemFolderEnabled(
                    enabled = true,
                    requestPermission = requestSystemStoragePermission,
                )
            },
            onSyncNow = viewModel::syncSystemFolderNow,
            onBack = { navController.popBackStack() },
        )
    }
}

/**
 * Subgrafo de navegación para las vistas inmersivas de audio:
 * reproductor completo, cola interactiva, ecualizador de 10 bandas y efectos Rust (8D/Reverb/Slowed/Crossfeed).
 */
internal fun NavGraphBuilder.auraPlayerAndAudioGraph(
    navController: NavHostController,
    viewModel: AuraViewModel,
    playbackState: PlaybackState,
    playbackTuning: PlaybackTuning,
    equalizerState: EqualizerState,
    audioEffects: AudioEffectsState,
    sleepTimerState: SleepTimerState,
    audioOutputRoute: AudioOutputRouteState,
    headphonePreferences: HeadphonePreferences,
    dynamicCoverColorsEnabled: Boolean,
    safLaunchers: AuraSafLaunchers,
    onRequestAddToPlaylist: (Track) -> Unit,
    onRequestEditMetadata: (Track) -> Unit,
) {
    composable(AuraRoutes.EQUALIZER) {
        EqualizerScreen(
            equalizerState = equalizerState,
            onBack = { navController.popBackStack() },
            onToggleEnabled = viewModel::setEqualizerEnabled,
            onBandGainChange = viewModel::setEqualizerBandGain,
            onSelectPreset = viewModel::selectEqualizerPreset,
            onReset = viewModel::resetEqualizer,
        )
    }

    composable(AuraRoutes.AUDIO_EFFECTS) {
        AudioEffectsScreen(
            effectsState = audioEffects,
            onBack = { navController.popBackStack() },
            onSelectPreset = viewModel::selectAudioEffectPreset,
            onSetReverbEnabled = viewModel::setReverbEnabled,
            onSetReverbRoomSize = viewModel::setReverbRoomSize,
            onSetReverbDamping = viewModel::setReverbDamping,
            onSetReverbWetMix = viewModel::setReverbWetMix,
            onSetReverbWidth = viewModel::setReverbWidth,
            onSetSlowedEnabled = viewModel::setSlowedEnabled,
            onSetSlowedSpeed = viewModel::setSlowedSpeed,
            onSetSlowedPreservePitch = viewModel::setSlowedPreservePitch,
            onSetSlowedWarmth = viewModel::setSlowedWarmth,
            onSetSpatial8dEnabled = viewModel::setSpatial8dEnabled,
            onSetSpatial8dSpeed = viewModel::setSpatial8dSpeed,
            onSetSpatial8dDepth = viewModel::setSpatial8dDepth,
            onReset = viewModel::resetAudioEffects,
            outputRoute = audioOutputRoute,
            headphonePreferences = headphonePreferences,
            onSetCrossfeedEnabled = viewModel::setCrossfeedEnabled,
            onSetCrossfeedStrength = viewModel::setCrossfeedStrength,
            onSetCrossfeedAngle = viewModel::setCrossfeedAngle,
        )
    }

    composable(AuraRoutes.PLAYER) {
        PlayerScreen(
            playbackState = playbackState,
            playbackTuning = playbackTuning,
            audioEffects = audioEffects,
            sleepTimerState = sleepTimerState,
            dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
            onCollapse = { navController.popBackStack() },
            onTogglePlayPause = viewModel::togglePlayPause,
            onSkipNext = viewModel::skipNext,
            onSkipPrevious = viewModel::skipPrevious,
            onSeekTo = viewModel::seekTo,
            onToggleShuffle = viewModel::toggleShuffle,
            onCycleRepeat = viewModel::cycleRepeat,
            onToggleLike = viewModel::toggleLike,
            onOpenAddToPlaylist = onRequestAddToPlaylist,
            onEditMetadata = onRequestEditMetadata,
            onChangeCover = { track -> safLaunchers.pickCustomCoverForTrack(track.id) },
            onOpenEqualizer = {
                navController.navigate(AuraRoutes.EQUALIZER) {
                    launchSingleTop = true
                }
            },
            onOpenAudioEffects = {
                navController.navigate(AuraRoutes.AUDIO_EFFECTS) {
                    launchSingleTop = true
                }
            },
            onOpenQueue = {
                navController.navigate(AuraRoutes.QUEUE) {
                    launchSingleTop = true
                }
            },
            onSetPlaybackSpeed = viewModel::setPlaybackSpeed,
            onSetPlaybackPitch = viewModel::setPlaybackPitch,
            onSetLinkPitchToSpeed = viewModel::setLinkPitchToSpeed,
            onResetSpeedAndPitch = viewModel::resetPlaybackSpeedAndPitch,
            onSetCrossfadeEnabled = viewModel::setCrossfadeEnabled,
            onSetCrossfadeSeconds = viewModel::setCrossfadeSeconds,
            onStartSleepTimerMinutes = viewModel::startSleepTimerMinutes,
            onSetSleepTimerEndOfTrack = viewModel::setSleepTimerEndOfTrack,
            onSetSleepTimerEndOfQueue = viewModel::setSleepTimerEndOfQueue,
            onAddSleepTimerMinutes = viewModel::addSleepTimerMinutes,
            onCancelSleepTimer = viewModel::cancelSleepTimer,
        )
    }

    composable(AuraRoutes.QUEUE) {
        QueueScreen(
            playbackState = playbackState,
            dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
            onBack = { navController.popBackStack() },
            onPlayQueueItemAt = viewModel::playQueueItemAt,
            onMoveQueueItem = viewModel::moveQueueItem,
            onRemoveFromQueue = viewModel::removeFromQueue,
            onClearUpcoming = viewModel::clearUpcomingQueue,
        )
    }
}
