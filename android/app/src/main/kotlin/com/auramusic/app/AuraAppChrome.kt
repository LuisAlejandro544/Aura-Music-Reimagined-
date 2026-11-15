package com.auramusic.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.auramusic.core.importer.AuraSystemStorageManager
import com.auramusic.core.model.PlaybackState
import com.auramusic.feature.player.MiniPlayerBar

/**
 * Encapsula los selectores del Storage Access Framework (SAF) y galería
 * para mantener limpia la composición principal en `AuraAppRoot`.
 */
@Stable
class AuraSafLaunchers(
    val pickSingleAudio: () -> Unit,
    val pickMultipleAudio: () -> Unit,
    val pickFolderTree: () -> Unit,
    val pickCustomCoverForTrack: (Long) -> Unit,
)

/**
 * Crea y recuerda los `ActivityResultLauncher` necesarios para importar canciones,
 * carpetas completas y carátulas personalizadas desde la galería del teléfono.
 */
@Composable
fun rememberAuraSafLaunchers(
    onImportFiles: (List<Uri>) -> Unit,
    onImportFolder: (Uri) -> Unit,
    onSetCustomTrackCover: (trackId: Long, imageUri: Uri) -> Unit,
): AuraSafLaunchers {
    var pendingCoverTrackId by remember { mutableStateOf<Long?>(null) }

    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onImportFiles(listOf(uri))
    }

    val multipleFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) onImportFiles(uris)
    }

    val folderTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) onImportFolder(treeUri)
    }

    val galleryCoverLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { imageUri ->
        val targetTrackId = pendingCoverTrackId
        pendingCoverTrackId = null
        if (imageUri != null && targetTrackId != null) {
            onSetCustomTrackCover(targetTrackId, imageUri)
        }
    }

    return remember(
        singleFileLauncher,
        multipleFilesLauncher,
        folderTreeLauncher,
        galleryCoverLauncher,
    ) {
        AuraSafLaunchers(
            pickSingleAudio = { singleFileLauncher.launch(arrayOf("audio/*")) },
            pickMultipleAudio = { multipleFilesLauncher.launch(arrayOf("audio/*")) },
            pickFolderTree = { folderTreeLauncher.launch(null) },
            pickCustomCoverForTrack = { trackId ->
                pendingCoverTrackId = trackId
                galleryCoverLauncher.launch("image/*")
            },
        )
    }
}

/**
 * Crea y recuerda el flujo de solicitud de permiso de almacenamiento para la carpeta
 * pública `Aura Music` (Android 8–10 mediante diálogo clásico y Android 11–15 mediante
 * `MANAGE_EXTERNAL_STORAGE` con respaldo en ajustes de la aplicación).
 */
@Composable
fun rememberAuraSystemStoragePermissionLauncher(
    context: Context,
    onPermissionEvaluated: (Boolean) -> Unit,
): () -> Unit {
    val manageFilesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onPermissionEvaluated(AuraSystemStorageManager.hasStoragePermission(context))
    }

    val legacyPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        onPermissionEvaluated(AuraSystemStorageManager.hasStoragePermission(context))
    }

    return remember(context, manageFilesLauncher, legacyPermissionsLauncher) {
        {
            if (AuraSystemStorageManager.hasStoragePermission(context)) {
                onPermissionEvaluated(true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val primaryIntent = AuraSystemStorageManager.buildManageAllFilesIntent(context)
                val launchedPrimary = runCatching {
                    manageFilesLauncher.launch(primaryIntent)
                }.isSuccess
                if (!launchedPrimary) {
                    val allFilesIntent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    val launchedFallback = runCatching {
                        manageFilesLauncher.launch(allFilesIntent)
                    }.isSuccess
                    if (!launchedFallback) {
                        val detailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                        runCatching { manageFilesLauncher.launch(detailsIntent) }
                    }
                }
            } else {
                legacyPermissionsLauncher.launch(
                    AuraSystemStorageManager.legacyStoragePermissions(),
                )
            }
        }
    }
}

/**
 * Barra inferior unificada de Aura Music: combina el `MiniPlayerBar` animado
 * con la barra de navegación entre Biblioteca, Playlists y Ajustes.
 */
@Composable
fun AuraBottomChrome(
    playbackState: PlaybackState,
    dynamicCoverColorsEnabled: Boolean,
    isLibrarySelected: Boolean,
    isPlaylistsSelected: Boolean,
    isSettingsSelected: Boolean,
    onOpenFullPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleLike: (Long) -> Unit,
    onSelectLibrary: () -> Unit,
    onSelectPlaylists: () -> Unit,
    onSelectSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.navigationBarsPadding()) {
        AnimatedVisibility(
            visible = playbackState.currentTrack != null,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.86f,
                    stiffness = Spring.StiffnessMedium,
                ),
            ) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(
                animationSpec = tween(170),
            ) { it } + fadeOut(tween(140)),
        ) {
            MiniPlayerBar(
                playbackState = playbackState,
                dynamicCoverColorsEnabled = dynamicCoverColorsEnabled,
                onOpenFullPlayer = onOpenFullPlayer,
                onTogglePlayPause = onTogglePlayPause,
                onSkipNext = onSkipNext,
                onToggleLike = onToggleLike,
            )
        }

        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            NavigationBarItem(
                selected = isLibrarySelected,
                onClick = onSelectLibrary,
                icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                label = { Text("Biblioteca") },
            )
            NavigationBarItem(
                selected = isPlaylistsSelected,
                onClick = onSelectPlaylists,
                icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                label = { Text("Playlists") },
            )
            NavigationBarItem(
                selected = isSettingsSelected,
                onClick = onSelectSettings,
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                label = { Text("Ajustes") },
            )
        }
    }
}
