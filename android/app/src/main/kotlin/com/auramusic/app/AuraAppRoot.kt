package com.auramusic.app

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.Track
import com.auramusic.core.model.TrackMetadataHelper
import com.auramusic.core.rust.AuraRustBridge
import com.auramusic.feature.downloader.DownloaderScreen
import com.auramusic.feature.effects.AudioEffectsScreen
import com.auramusic.feature.equalizer.EqualizerScreen
import com.auramusic.feature.library.LibraryScreen
import com.auramusic.feature.player.PlayerScreen
import com.auramusic.feature.player.QueueScreen
import com.auramusic.feature.playlists.AddToPlaylistBottomSheet
import com.auramusic.feature.playlists.ArtistDetailScreen
import com.auramusic.feature.playlists.LikedSongsScreen
import com.auramusic.feature.playlists.PlaylistDetailScreen
import com.auramusic.feature.playlists.PlaylistsScreen
import com.auramusic.feature.settings.SettingsAccentThemeScreen
import com.auramusic.feature.settings.SettingsHeadphonesScreen
import com.auramusic.feature.settings.SettingsMediaButtonsScreen
import com.auramusic.feature.settings.SettingsScreen
import com.auramusic.feature.settings.SettingsVisualEffectsScreen
import kotlinx.coroutines.launch

private object AuraRoutes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_VISUAL_FX = "settings/visual_fx"
    const val SETTINGS_HEADPHONES = "settings/headphones"
    const val SETTINGS_MEDIA_BUTTONS = "settings/media_buttons"
    const val EQUALIZER = "equalizer"
    const val AUDIO_EFFECTS = "audio_effects"
    const val DOWNLOADER = "downloader"
    const val LIKED = "liked"
    const val PLAYER = "player"
    const val QUEUE = "queue"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val ARTIST_DETAIL = "artist/{artistKey}"

    fun playlistDetail(playlistId: Long): String = "playlist/$playlistId"
    fun artistDetail(artistKey: String): String = "artist/${Uri.encode(artistKey)}"
}

/**
 * Raíz de navegación de Aura Music.
 * Separa claramente cada apartado en pantallas dedicadas (Biblioteca, Playlists, Favoritos,
 * Detalle de Playlist, Reproductor completo y Ajustes), conectadas por la barra inferior y el MiniPlayer.
 */
@Composable
fun AuraAppRoot(viewModel: AuraViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val likedCount by viewModel.likedCount.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val artistPlaylists by viewModel.artistPlaylists.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val sleepTimerState by viewModel.sleepTimerState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val downloaderEngineStatus by viewModel.downloaderEngineStatus.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val dynamicCoverColorsEnabled by viewModel.dynamicCoverColorsEnabled.collectAsStateWithLifecycle()
    val deleteAnimationsEnabled by viewModel.deleteAnimationsEnabled.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()
    val audioEffects by viewModel.audioEffects.collectAsStateWithLifecycle()
    val playbackTuning by viewModel.playbackTuning.collectAsStateWithLifecycle()
    val headphonePreferences by viewModel.headphonePreferences.collectAsStateWithLifecycle()
    val audioOutputRoute by viewModel.audioOutputRoute.collectAsStateWithLifecycle()
    val rustEngineInfo by viewModel.rustEngineInfo.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val showQueueFeedback: (String) -> Unit = { message ->
        uiScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.openPlayerEvents.collect {
            navController.navigate(AuraRoutes.PLAYER) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    // Puente desacoplado hacia el renderizador nativo de animaciones de eliminación en Rust.
    val deleteEffectRenderer = remember {
        DeleteEffectFrameRenderer { src, dst, width, height, effect, progress, seed, accentArgb ->
            AuraRustBridge.renderDeleteEffectFrame(
                sourceBuffer = src,
                outputBuffer = dst,
                width = width,
                height = height,
                effectType = effect.id,
                progress = progress,
                seed = seed,
                accentArgb = accentArgb,
            )
        }
    }

    var trackForPlaylistSheet by remember { mutableStateOf<Track?>(null) }
    var trackPendingMetadataEdit by remember { mutableStateOf<Track?>(null) }

    val safLaunchers = rememberAuraSafLaunchers(
        onImportFiles = viewModel::importFiles,
        onImportFolder = viewModel::importFolder,
        onSetCustomTrackCover = viewModel::setCustomTrackCover,
    )

    val navigateTopTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val showBottomChrome = currentRoute != AuraRoutes.PLAYER &&
        currentRoute != AuraRoutes.EQUALIZER &&
        currentRoute != AuraRoutes.AUDIO_EFFECTS &&
        currentRoute != AuraRoutes.DOWNLOADER &&
        currentRoute != AuraRoutes.QUEUE

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (showBottomChrome) {
                AuraBottomChrome(
                    playbackState = playbackState,
                    dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
                    isLibrarySelected = currentRoute == AuraRoutes.LIBRARY,
                    isPlaylistsSelected = currentRoute == AuraRoutes.PLAYLISTS ||
                        currentRoute == AuraRoutes.LIKED ||
                        currentRoute == AuraRoutes.PLAYLIST_DETAIL ||
                        currentRoute == AuraRoutes.ARTIST_DETAIL,
                    isSettingsSelected = currentRoute == AuraRoutes.SETTINGS ||
                        currentRoute == AuraRoutes.SETTINGS_THEME ||
                        currentRoute == AuraRoutes.SETTINGS_VISUAL_FX ||
                        currentRoute == AuraRoutes.SETTINGS_HEADPHONES ||
                        currentRoute == AuraRoutes.SETTINGS_MEDIA_BUTTONS,
                    onOpenFullPlayer = {
                        navController.navigate(AuraRoutes.PLAYER) {
                            launchSingleTop = true
                        }
                    },
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onSkipNext = viewModel::skipNext,
                    onToggleLike = viewModel::toggleLike,
                    onSelectLibrary = { navigateTopTab(AuraRoutes.LIBRARY) },
                    onSelectPlaylists = { navigateTopTab(AuraRoutes.PLAYLISTS) },
                    onSelectSettings = { navigateTopTab(AuraRoutes.SETTINGS) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
            contentAlignment = Alignment.Center,
        ) {
            NavHost(
                navController = navController,
                startDestination = AuraRoutes.LIBRARY,
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
                            showQueueFeedback("Se reproducirá a continuación: ${track.title}")
                        },
                        onAddToQueue = { track ->
                            viewModel.addToQueue(track)
                            showQueueFeedback("Añadida a la cola: ${track.title}")
                        },
                        onAddToPlaylist = { track -> trackForPlaylistSheet = track },
                        onEditMetadata = { track -> trackPendingMetadataEdit = track },
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
                            showQueueFeedback("Se reproducirá a continuación: ${track.title}")
                        },
                        onAddToQueue = { track ->
                            viewModel.addToQueue(track)
                            showQueueFeedback("Añadida a la cola: ${track.title}")
                        },
                        onAddToPlaylist = { track -> trackForPlaylistSheet = track },
                        onEditMetadata = { track -> trackPendingMetadataEdit = track },
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
                            showQueueFeedback("Se reproducirá a continuación: ${track.title}")
                        },
                        onAddToQueue = { track ->
                            viewModel.addToQueue(track)
                            showQueueFeedback("Añadida a la cola: ${track.title}")
                        },
                        onEditMetadata = { track -> trackPendingMetadataEdit = track },
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
                            showQueueFeedback("Se reproducirá a continuación: ${track.title}")
                        },
                        onAddToQueue = { track ->
                            viewModel.addToQueue(track)
                            showQueueFeedback("Añadida a la cola: ${track.title}")
                        },
                        onAddToPlaylist = { track -> trackForPlaylistSheet = track },
                        onEditMetadata = { track -> trackPendingMetadataEdit = track },
                    )
                }

                composable(AuraRoutes.SETTINGS) {
                    SettingsScreen(
                        selectedAccent = accentColor,
                        dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
                        deleteAnimationsEnabled = deleteAnimationsEnabled,
                        rustEngineInfo = rustEngineInfo,
                        outputRoute = audioOutputRoute,
                        headphonePreferences = headphonePreferences,
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
                    )
                }

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
                        onOpenAddToPlaylist = { track -> trackForPlaylistSheet = track },
                        onEditMetadata = { track -> trackPendingMetadataEdit = track },
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
        }
    }

    val pendingTrack = trackForPlaylistSheet
    if (pendingTrack != null) {
        AddToPlaylistBottomSheet(
            track = pendingTrack,
            playlists = playlists,
            onDismiss = { trackForPlaylistSheet = null },
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToPlaylist(playlistId, pendingTrack.id)
                trackForPlaylistSheet = null
            },
            onCreatePlaylistAndAdd = { name ->
                viewModel.createPlaylistAndAddTrack(name, pendingTrack.id)
                trackForPlaylistSheet = null
            },
        )
    }

    val editingTrack = trackPendingMetadataEdit
    if (editingTrack != null) {
        EditTrackMetadataDialog(
            track = editingTrack,
            onDismiss = { trackPendingMetadataEdit = null },
            onSave = { newTitle, newArtist ->
                viewModel.updateTrackMetadata(
                    trackId = editingTrack.id,
                    title = newTitle,
                    artist = newArtist,
                )
                trackPendingMetadataEdit = null
            },
        )
    }
}

/**
 * Popup modal para editar localmente el nombre de la canción y el nombre del artista.
 * Solo modifica los registros de Room sin reescribir el archivo de audio original.
 */
@Composable
private fun EditTrackMetadataDialog(
    track: Track,
    onDismiss: () -> Unit,
    onSave: (title: String, artist: String) -> Unit,
) {
    var titleInput by remember(track.id) { mutableStateOf(track.title) }
    var artistInput by remember(track.id) { mutableStateOf(track.artist) }
    val normalizedPreview = TrackMetadataHelper.normalizeOrNull(titleInput, artistInput)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = "Editar metadatos",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = titleInput,
                    onValueChange = { next ->
                        if (next.length <= TrackMetadataHelper.MAX_TITLE_LENGTH) {
                            titleInput = next
                        }
                    },
                    label = { Text("Nombre de la canción") },
                    placeholder = { Text("Ej. Me Rehúso") },
                    singleLine = true,
                    isError = titleInput.trim().isEmpty(),
                    supportingText = if (titleInput.trim().isEmpty()) {
                        { Text("El nombre de la canción no puede estar vacío") }
                    } else {
                        null
                    },
                    trailingIcon = {
                        if (titleInput.isNotEmpty()) {
                            IconButton(onClick = { titleInput = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Limpiar nombre de la canción",
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = artistInput,
                    onValueChange = { next ->
                        if (next.length <= TrackMetadataHelper.MAX_ARTIST_LENGTH) {
                            artistInput = next
                        }
                    },
                    label = { Text("Nombre del artista") },
                    placeholder = { Text(TrackMetadataHelper.DEFAULT_ARTIST) },
                    singleLine = true,
                    trailingIcon = {
                        if (artistInput.isNotEmpty()) {
                            IconButton(onClick = { artistInput = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Limpiar artista",
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Si dejas el artista vacío se guardará como «${TrackMetadataHelper.DEFAULT_ARTIST}». Los cambios actualizan al instante la biblioteca, artistas y notificación.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = normalizedPreview ?: return@Button
                    onSave(clean.title, clean.artist)
                },
                enabled = normalizedPreview != null,
            ) {
                Text("Guardar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

/**
 * Pantalla de carga inicial mientras Room, DataStore y el motor nativo Rust
 * terminan de cargar la biblioteca del usuario, evitando parpadeos de pantalla vacía.
 */
@Composable
fun AuraStartupLoadingScreen() {
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.size(96.dp),
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF151028),
                tonalElevation = 8.dp,
                shadowElevation = 14.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_aura_brand_logo),
                        contentDescription = "Aura Music",
                        modifier = Modifier.size(80.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Aura Music",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Cargando tu biblioteca local...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(26.dp))

            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}
