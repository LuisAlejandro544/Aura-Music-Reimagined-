package com.auramusic.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.launch

/**
 * Orquestador raíz de navegación y estado global de Aura Music.
 * Conecta `AuraViewModel` con los subgrafos modulares (`AuraNavGraph.kt`),
 * la barra inferior compartida (`AuraAppChrome.kt`) y los modales globales (`AuraAppDialogs.kt`).
 */
@Composable
fun AuraAppRoot(viewModel: AuraViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val dynamicCoverColorsEnabled by viewModel.dynamicCoverColorsEnabled.collectAsStateWithLifecycle()
    val appContext = LocalContext.current

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
    val requestSystemStoragePermission = rememberAuraSystemStoragePermissionLauncher(
        context = appContext,
        onPermissionEvaluated = viewModel::onSystemFolderPermissionResult,
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (AuraRoutes.shouldShowBottomChrome(currentRoute)) {
                AuraBottomChrome(
                    playbackState = playbackState,
                    dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
                    isLibrarySelected = currentRoute == AuraRoutes.LIBRARY,
                    isPlaylistsSelected = AuraRoutes.isPlaylistsSection(currentRoute),
                    isSettingsSelected = AuraRoutes.isSettingsSection(currentRoute),
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
                enterTransition = { AuraNavTransitions.enterTransition(this) },
                exitTransition = { AuraNavTransitions.exitTransition(this) },
                popEnterTransition = { AuraNavTransitions.popEnterTransition(this) },
                popExitTransition = { AuraNavTransitions.popExitTransition(this) },
            ) {
                auraLibraryAndPlaylistsGraph(
                    navController = navController,
                    viewModel = viewModel,
                    deleteEffectRenderer = deleteEffectRenderer,
                    safLaunchers = safLaunchers,
                    onShowQueueFeedback = showQueueFeedback,
                    onRequestAddToPlaylist = { track -> trackForPlaylistSheet = track },
                    onRequestEditMetadata = { track -> trackPendingMetadataEdit = track },
                )

                auraSettingsGraph(
                    navController = navController,
                    viewModel = viewModel,
                    requestSystemStoragePermission = requestSystemStoragePermission,
                )

                auraPlayerAndAudioGraph(
                    navController = navController,
                    viewModel = viewModel,
                    safLaunchers = safLaunchers,
                    onRequestAddToPlaylist = { track -> trackForPlaylistSheet = track },
                    onRequestEditMetadata = { track -> trackPendingMetadataEdit = track },
                )
            }
        }
    }

    AuraTrackModalHost(
        trackForPlaylistSheet = trackForPlaylistSheet,
        trackPendingMetadataEdit = trackPendingMetadataEdit,
        playlists = playlists,
        onDismissPlaylistSheet = { trackForPlaylistSheet = null },
        onAddTrackToPlaylist = viewModel::addTrackToPlaylist,
        onCreatePlaylistAndAddTrack = viewModel::createPlaylistAndAddTrack,
        onDismissMetadataEdit = { trackPendingMetadataEdit = null },
        onSaveTrackMetadata = viewModel::updateTrackMetadata,
    )
}
