package com.auramusic.feature.library

import android.graphics.Bitmap
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.auramusic.core.model.DeleteAnimationEffect
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pantalla principal de Biblioteca local de Aura Music.
 * Permite importar música de forma manual (1 canción, varias canciones o carpeta completa),
 * buscar canciones, marcar "Me gusta" y reproducir al instante con ExoPlayer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    tracks: List<Track>,
    likedCount: Int,
    currentPlayingTrackId: Long?,
    importProgress: ImportProgress,
    onImportSingleTrack: () -> Unit,
    onImportMultipleTracks: () -> Unit,
    onImportFolder: () -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onChangeCover: (Track) -> Unit,
    onDeleteTrack: (Long) -> Unit,
    onOpenLikedSongs: () -> Unit,
    onDismissImportMessage: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onEditMetadata: (Track) -> Unit = {},
    onOpenDownloader: () -> Unit = {},
    deleteAnimationsEnabled: Boolean = true,
    accentArgb: Int = 0xFFA78BFA.toInt(),
    deleteEffectRenderer: DeleteEffectFrameRenderer? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    var showImportSheet by remember { mutableStateOf(false) }
    var trackPendingDelete by remember { mutableStateOf<Track?>(null) }
    val deletingEffects = remember { mutableStateMapOf<Long, DeleteAnimationEffect>() }

    val filteredTracks = remember(tracks, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            tracks
        } else {
            tracks.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.album.lowercase().contains(q)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Aura Music",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${tracks.size} canciones en tu biblioteca",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showImportSheet = true },
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Importar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (importProgress.isImporting) {
                ImportProgressBanner(importProgress = importProgress)
            } else if (importProgress.lastMessage != null) {
                ImportResultBanner(
                    message = importProgress.lastMessage.orEmpty(),
                    onDismiss = onDismissImportMessage,
                )
            }

            if (tracks.isEmpty()) {
                EmptyLibraryState(
                    onImportSingleTrack = onImportSingleTrack,
                    onImportMultipleTracks = onImportMultipleTracks,
                    onImportFolder = onImportFolder,
                    onOpenDownloader = onOpenDownloader,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 6.dp),
                            placeholder = { Text("Buscar por título, artista o álbum") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null)
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Limpiar búsqueda")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )
                    }

                    item {
                        LibrarySummaryStrip(
                            totalTracks = tracks.size,
                            likedCount = likedCount,
                            onPlayAll = { onPlayTrack(filteredTracks, 0) },
                            onOpenLikedSongs = onOpenLikedSongs,
                        )
                    }

                    itemsIndexed(
                        items = filteredTracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        RustDeleteEffectHost(
                            trackId = track.id,
                            activeEffect = deletingEffects[track.id],
                            accentArgb = accentArgb,
                            renderer = deleteEffectRenderer,
                            onAnimationFinished = {
                                deletingEffects.remove(track.id)
                                onDeleteTrack(track.id)
                            },
                        ) {
                            TrackRowCard(
                                track = track,
                                isCurrent = track.id == currentPlayingTrackId,
                                onPlay = { onPlayTrack(filteredTracks, index) },
                                onToggleLike = { onToggleLike(track.id) },
                                onPlayNext = { onPlayNext(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onEditMetadata = { onEditMetadata(track) },
                                onChangeCover = { onChangeCover(track) },
                                onDelete = { trackPendingDelete = track },
                            )
                        }
                    }
                }
            }
        }
    }

    val pendingDelete = trackPendingDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { trackPendingDelete = null },
            title = { Text("Eliminar canción") },
            text = {
                Text(
                    "¿Seguro que quieres quitar \"${pendingDelete.title}\" de Aura Music? Se quitará también de tus playlists.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pendingDelete
                        trackPendingDelete = null
                        if (deleteAnimationsEnabled && deleteEffectRenderer != null) {
                            deletingEffects[target.id] = DeleteAnimationEffect.random()
                        } else {
                            onDeleteTrack(target.id)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { trackPendingDelete = null }) {
                    Text("Cancelar")
                }
            },
        )
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.85f,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Importar música local",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Aura Music nunca escanea todo tu teléfono. Tú eliges exactamente qué canciones o carpetas agregar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))

                ImportOptionCard(
                    icon = Icons.Rounded.MusicNote,
                    title = "Importar una canción",
                    subtitle = "Selecciona un archivo individual (.mp3, .flac, .m4a, .ogg...)",
                    onClick = {
                        showImportSheet = false
                        onImportSingleTrack()
                    },
                )
                ImportOptionCard(
                    icon = Icons.Rounded.AudioFile,
                    title = "Importar varias canciones",
                    subtitle = "Elige una tanda de archivos de audio a la vez",
                    onClick = {
                        showImportSheet = false
                        onImportMultipleTracks()
                    },
                )
                ImportOptionCard(
                    icon = Icons.Rounded.CreateNewFolder,
                    title = "Importar carpeta completa",
                    subtitle = "Agrega todas las canciones dentro de una carpeta y subcarpetas",
                    onClick = {
                        showImportSheet = false
                        onImportFolder()
                    },
                )
                ImportOptionCard(
                    icon = Icons.Rounded.CloudDownload,
                    title = "Descargar audio por enlace",
                    subtitle = "Usa yt-dlp + yt-dlp-ejs + QuickJS-NG para añadir audio directo a tu biblioteca",
                    onClick = {
                        showImportSheet = false
                        onOpenDownloader()
                    },
                )
            }
        }
    }
}

@Composable
private fun LibrarySummaryStrip(
    totalTracks: Int,
    likedCount: Int,
    onPlayAll: () -> Unit,
    onOpenLikedSongs: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Button(
            onClick = onPlayAll,
            enabled = totalTracks > 0,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reproducir todo")
        }

        FilledTonalButton(
            onClick = onOpenLikedSongs,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.Favorite, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Me gusta ($likedCount)")
        }
    }
}

@Composable
private fun TrackRowCard(
    track: Track,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditMetadata: () -> Unit,
    onChangeCover: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(14.dp),
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        tonalElevation = if (isCurrent) 4.dp else 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                val context = LocalContext.current
                val file = track.coverFilePath?.let(::File)?.takeIf { it.exists() }
                if (file != null) {
                    val thumbRequest = remember(file.absolutePath, file.lastModified()) {
                        ImageRequest.Builder(context)
                            .data(file)
                            .size(168)
                            .build()
                    }
                    AsyncImage(
                        model = thumbRequest,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null)
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "Sonando",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 4.dp),
                        )
                    }
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${track.artist} • ${formatTrackDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onToggleLike) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (track.isLiked) "Quitar me gusta" else "Me gusta",
                    tint = if (track.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                val menuIconRotation = rememberLibraryMenuTriggerRotation(menuExpanded)
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Opciones de ${track.title}",
                        modifier = Modifier.graphicsLayer {
                            rotationZ = menuIconRotation
                        },
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    AuraLibraryMenuItemReveal(index = 0) {
                        DropdownMenuItem(
                            text = { Text("Reproducir siguiente") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onPlayNext()
                            },
                        )
                    }
                    AuraLibraryMenuItemReveal(index = 1) {
                        DropdownMenuItem(
                            text = { Text("Añadir a la cola") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onAddToQueue()
                            },
                        )
                    }
                    AuraLibraryMenuItemReveal(index = 2) {
                        DropdownMenuItem(
                            text = { Text("Añadir a playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onAddToPlaylist()
                            },
                        )
                    }
                    AuraLibraryMenuItemReveal(index = 3) {
                        DropdownMenuItem(
                            text = { Text("Editar metadatos") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEditMetadata()
                            },
                        )
                    }
                    AuraLibraryMenuItemReveal(index = 4) {
                        DropdownMenuItem(
                            text = { Text("Cambiar carátula") },
                            leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onChangeCover()
                            },
                        )
                    }
                    AuraLibraryMenuItemReveal(index = 5) {
                        DropdownMenuItem(
                            text = { Text("Quitar de Aura Music") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryState(
    onImportSingleTrack: () -> Unit,
    onImportMultipleTracks: () -> Unit,
    onImportFolder: () -> Unit,
    onOpenDownloader: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(84.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "Tu biblioteca está lista",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Importa canciones sueltas, varias a la vez o una carpeta completa. " +
                "Las pistas sin carátula recibirán una portada procedural creada en Rust.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(22.dp))

        Button(
            onClick = onImportFolder,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.CreateNewFolder, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Importar carpeta completa")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onImportSingleTrack,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("1 canción")
            }
            OutlinedButton(
                onClick = onImportMultipleTracks,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("Varias canciones")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        FilledTonalButton(
            onClick = onOpenDownloader,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Descargar audio por enlace")
        }
    }
}

@Composable
private fun ImportOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(subtitle) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

@Composable
private fun ImportProgressBanner(importProgress: ImportProgress) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            val status = if (importProgress.totalFiles > 0) {
                "Procesando ${importProgress.processedFiles} de ${importProgress.totalFiles} archivos..."
            } else {
                importProgress.lastMessage ?: "Importando música..."
            }
            Text(
                text = status,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (importProgress.totalFiles > 0) {
                LinearProgressIndicator(
                    progress = { importProgress.processedFiles.toFloat() / importProgress.totalFiles.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ImportResultBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Cerrar aviso",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * Captura la tarjeta de canción y delega en Rust la animación cuadro a cuadro
 * (cenizas, papel quemándose o aura extraña) antes de ejecutar la eliminación real en Room.
 */
@Composable
private fun RustDeleteEffectHost(
    trackId: Long,
    activeEffect: DeleteAnimationEffect?,
    accentArgb: Int,
    renderer: DeleteEffectFrameRenderer?,
    onAnimationFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val captureLayer = rememberGraphicsLayer()
    var renderedFrame by remember(trackId, activeEffect) { mutableStateOf<ImageBitmap?>(null) }
    var collapseFraction by remember(trackId, activeEffect) { mutableFloatStateOf(0f) }
    var isFinished by remember(trackId, activeEffect) { mutableStateOf(false) }

    LaunchedEffect(trackId, activeEffect) {
        if (activeEffect == null) return@LaunchedEffect
        if (renderer == null) {
            isFinished = true
            onAnimationFinished()
            return@LaunchedEffect
        }

        try {
            // Espera un frame para garantizar que la tarjeta esté dibujada sin el modal encima.
            withFrameNanos { }
            val capturedBitmap = captureLayer.toImageBitmap().asAndroidBitmap()
            if (capturedBitmap.width <= 0 || capturedBitmap.height <= 0) {
                return@LaunchedEffect
            }

            val prepared = withContext(Dispatchers.Default) {
                val softwareBitmap = if (capturedBitmap.config != Bitmap.Config.ARGB_8888) {
                    capturedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                } else {
                    capturedBitmap
                } ?: return@withContext null

                val targetWidth = softwareBitmap.width.coerceAtMost(340).coerceAtLeast(1)
                val targetHeight = ((softwareBitmap.height.toFloat() / softwareBitmap.width.toFloat()) * targetWidth)
                    .toInt()
                    .coerceAtLeast(1)
                val scaledBitmap = if (targetWidth != softwareBitmap.width || targetHeight != softwareBitmap.height) {
                    softwareBitmap.scale(targetWidth, targetHeight)
                } else {
                    softwareBitmap
                }
                if (softwareBitmap !== capturedBitmap && scaledBitmap !== softwareBitmap) {
                    softwareBitmap.recycle()
                }

                val byteCount = targetWidth * targetHeight * 4
                val srcBuffer = ByteBuffer.allocateDirect(byteCount)
                scaledBitmap.copyPixelsToBuffer(srcBuffer)
                srcBuffer.rewind()
                if (scaledBitmap !== capturedBitmap) {
                    scaledBitmap.recycle()
                }

                Triple(targetWidth, targetHeight, srcBuffer)
            } ?: return@LaunchedEffect

            val (width, height, srcBuffer) = prepared
            val dstBuffer = ByteBuffer.allocateDirect(width * height * 4)
            val frameBitmapA = createBitmap(width, height)
            val frameBitmapB = createBitmap(width, height)
            var useBufferA = true

            val startNanos = System.nanoTime()
            val durationMs = 1050L
            val seed = (trackId xor startNanos).toInt()

            while (true) {
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
                val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                collapseFraction = if (progress > 0.85f) {
                    ((progress - 0.85f) / 0.15f).coerceIn(0f, 1f)
                } else {
                    0f
                }

                srcBuffer.rewind()
                dstBuffer.rewind()
                val ok = renderer.renderFrame(
                    sourceDirectRgba = srcBuffer,
                    outputDirectRgba = dstBuffer,
                    width = width,
                    height = height,
                    effect = activeEffect,
                    progress = progress,
                    seed = seed,
                    accentArgb = accentArgb,
                )
                if (ok) {
                    dstBuffer.rewind()
                    val targetBmp = if (useBufferA) frameBitmapA else frameBitmapB
                    targetBmp.copyPixelsFromBuffer(dstBuffer)
                    renderedFrame = targetBmp.asImageBitmap()
                    useBufferA = !useBufferA
                }
                if (progress >= 1f) break
                withFrameNanos { }
            }
        } finally {
            renderedFrame = null
            isFinished = true
            onAnimationFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val targetHeight = (placeable.height * (1f - collapseFraction))
                    .roundToInt()
                    .coerceAtLeast(0)
                layout(placeable.width, targetHeight) {
                    placeable.placeRelative(0, 0)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = if (renderedFrame == null && !isFinished) 1f else 0f
                }
                .drawWithContent {
                    if (renderedFrame == null && !isFinished) {
                        captureLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(captureLayer)
                    }
                },
        ) {
            content()
        }

        val frame = renderedFrame
        if (frame != null && !isFinished) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawImage(
                    image = frame,
                    dstSize = IntSize(
                        width = size.width.roundToInt().coerceAtLeast(1),
                        height = size.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }
    }
}

/**
 * Anima la rotación del botón de tres puntos (`MoreVert`) al desplegar o cerrar
 * el menú de opciones de una canción en la Biblioteca.
 */
@Composable
private fun rememberLibraryMenuTriggerRotation(expanded: Boolean): Float {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.74f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "library_menu_trigger_rotation",
    )
    return rotation
}

/**
 * Revela cada fila del `DropdownMenu` en cascada rápida usando únicamente `graphicsLayer`
 * (alpha, desplazamiento y micro-escala) para evitar saltos de medida en el popup.
 */
@Composable
private fun AuraLibraryMenuItemReveal(
    index: Int,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (index > 0) {
            delay((index * 18L).coerceAtMost(90L))
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 155,
                easing = FastOutSlowInEasing,
            ),
        )
    }
    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            translationY = (1f - p) * -14f
            val scale = 0.94f + 0.06f * p
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.88f, 0f)
        },
    ) {
        content()
    }
}

private fun formatTrackDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
