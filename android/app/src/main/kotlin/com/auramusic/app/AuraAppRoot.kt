package com.auramusic.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.auramusic.core.model.Track
import com.auramusic.feature.equalizer.EqualizerScreen
import com.auramusic.feature.library.LibraryScreen
import com.auramusic.feature.player.MiniPlayerBar
import com.auramusic.feature.player.PlayerScreen
import com.auramusic.feature.playlists.AddToPlaylistBottomSheet
import com.auramusic.feature.playlists.LikedSongsScreen
import com.auramusic.feature.playlists.PlaylistDetailScreen
import com.auramusic.feature.playlists.PlaylistsScreen
import com.auramusic.feature.settings.SettingsScreen

private object AuraRoutes {
    const val LIBRARY = "library"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val EQUALIZER = "equalizer"
    const val LIKED = "liked"
    const val PLAYER = "player"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"

    fun playlistDetail(playlistId: Long): String = "playlist/$playlistId"
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
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val dynamicCoverColorsEnabled by viewModel.dynamicCoverColorsEnabled.collectAsStateWithLifecycle()
    val equalizerState by viewModel.equalizerState.collectAsStateWithLifecycle()
    val rustEngineInfo by viewModel.rustEngineInfo.collectAsStateWithLifecycle()

    var trackForPlaylistSheet by remember { mutableStateOf<Track?>(null) }
    var pendingCoverTrackId by remember { mutableStateOf<Long?>(null) }

    // Launchers del Storage Access Framework (SAF) para los 3 modos de importación manual.
    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFiles(listOf(uri))
    }

    val multipleFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }

    val folderTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) viewModel.importFolder(treeUri)
    }

    val galleryCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { imageUri ->
        val targetTrackId = pendingCoverTrackId
        pendingCoverTrackId = null
        if (imageUri != null && targetTrackId != null) {
            viewModel.setCustomTrackCover(targetTrackId, imageUri)
        }
    }

    val showBottomChrome = currentRoute != AuraRoutes.PLAYER && currentRoute != AuraRoutes.EQUALIZER

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomChrome) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    AnimatedVisibility(
                        visible = playbackState.currentTrack != null,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
                    ) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
                            onOpenFullPlayer = {
                                navController.navigate(AuraRoutes.PLAYER) {
                                    launchSingleTop = true
                                }
                            },
                            onTogglePlayPause = viewModel::togglePlayPause,
                            onSkipNext = viewModel::skipNext,
                            onToggleLike = viewModel::toggleLike,
                        )
                    }

                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        NavigationBarItem(
                            selected = currentRoute == AuraRoutes.LIBRARY,
                            onClick = {
                                navController.navigate(AuraRoutes.LIBRARY) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                            label = { Text("Biblioteca") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == AuraRoutes.PLAYLISTS ||
                                currentRoute == AuraRoutes.LIKED ||
                                currentRoute == AuraRoutes.PLAYLIST_DETAIL,
                            onClick = {
                                navController.navigate(AuraRoutes.PLAYLISTS) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                            label = { Text("Playlists") },
                        )
                        NavigationBarItem(
                            selected = currentRoute == AuraRoutes.SETTINGS,
                            onClick = {
                                navController.navigate(AuraRoutes.SETTINGS) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                            label = { Text("Ajustes") },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (showBottomChrome) innerPadding.calculateBottomPadding() else innerPadding.calculateBottomPadding()),
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
                        onImportSingleTrack = { singleFileLauncher.launch(arrayOf("audio/*")) },
                        onImportMultipleTracks = { multipleFilesLauncher.launch(arrayOf("audio/*")) },
                        onImportFolder = { folderTreeLauncher.launch(null) },
                        onPlayTrack = viewModel::playQueue,
                        onToggleLike = viewModel::toggleLike,
                        onAddToPlaylist = { track -> trackForPlaylistSheet = track },
                        onChangeCover = { track ->
                            pendingCoverTrackId = track.id
                            galleryCoverLauncher.launch("image/*")
                        },
                        onDeleteTrack = viewModel::deleteTrack,
                        onOpenLikedSongs = { navController.navigate(AuraRoutes.LIKED) },
                        onDismissImportMessage = viewModel::dismissImportMessage,
                    )
                }

                composable(AuraRoutes.PLAYLISTS) {
                    PlaylistsScreen(
                        playlists = playlists,
                        likedCount = likedCount,
                        onOpenLikedSongs = { navController.navigate(AuraRoutes.LIKED) },
                        onOpenPlaylist = { playlistId ->
                            navController.navigate(AuraRoutes.playlistDetail(playlistId))
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
                    )
                }

                composable(AuraRoutes.SETTINGS) {
                    SettingsScreen(
                        selectedAccent = accentColor,
                        dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
                        rustEngineInfo = rustEngineInfo,
                        onSelectAccent = viewModel::selectAccentColor,
                        onToggleDynamicCoverColors = viewModel::setDynamicCoverColorsEnabled,
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

                composable(AuraRoutes.PLAYER) {
                    PlayerScreen(
                        playbackState = playbackState,
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
                        onChangeCover = { track ->
                            pendingCoverTrackId = track.id
                            galleryCoverLauncher.launch("image/*")
                        },
                        onOpenEqualizer = {
                            navController.navigate(AuraRoutes.EQUALIZER) {
                                launchSingleTop = true
                            }
                        },
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
                modifier = Modifier.size(88.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "Aura Music",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(46.dp),
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
