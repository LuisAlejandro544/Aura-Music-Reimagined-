package com.auramusic.feature.playlists

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.auramusic.core.model.ArtistPlaylist
import com.auramusic.core.model.DeleteAnimationEffect
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Pantalla principal de Playlists.
 * Organiza dos pestañas claras:
 * - "Mis playlists": colección automática "Me gusta" y playlists creadas por el usuario.
 * - "Artistas": playlists generadas automáticamente agrupando canciones por nombre de artista.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    playlists: List<Playlist>,
    likedCount: Int,
    artistPlaylists: List<ArtistPlaylist> = emptyList(),
    onOpenLikedSongs: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onPlayArtist: (ArtistPlaylist) -> Unit = {},
    onCreatePlaylist: (String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Playlists y Artistas",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (selectedTab == 0) {
                        FilledTonalButton(
                            onClick = { showCreateDialog = true },
                            modifier = Modifier.padding(end = 12.dp),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Nueva")
                        }
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
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Mis playlists (${playlists.size})") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Artistas (${artistPlaylists.size})") },
                )
            }

            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenLikedSongs),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primaryContainer,
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                            ),
                                        ),
                                    )
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    modifier = Modifier.size(58.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.Favorite,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(30.dp),
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Canciones que te gustan",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$likedCount ${if (likedCount == 1) "canción favorita" else "canciones favoritas"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                    )
                                }
                            }
                        }
                    }

                    if (playlists.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.QueueMusic,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Aún no tienes playlists",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Organiza tus canciones locales por estado de ánimo, género o momento.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Button(onClick = { showCreateDialog = true }) {
                                        Text("Crear mi primera playlist")
                                    }
                                }
                            }
                        }
                    } else {
                        items(playlists, key = { it.id }) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                onOpen = { onOpenPlaylist(playlist.id) },
                                onDelete = { onDeletePlaylist(playlist.id) },
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "artist_auto_info") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Agrupación automática por artista: las canciones del mismo artista se juntan aquí y solo suena ese artista al reproducir su lista.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    if (artistPlaylists.isEmpty()) {
                        item {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = "Sin artistas en tu biblioteca",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Importa o descarga canciones y Aura Music creará automáticamente una playlist por cada artista.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        items(artistPlaylists, key = { it.artistKey }) { artist ->
                            ArtistPlaylistCard(
                                artistPlaylist = artist,
                                onOpen = { onOpenArtist(artist.artistKey) },
                                onPlayOnlyArtist = { onPlayArtist(artist) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                showCreateDialog = false
            },
        )
    }
}

/**
 * Pantalla dedicada de "Canciones que te gustan".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsScreen(
    tracks: List<Track>,
    currentPlayingTrackId: Long?,
    onBack: () -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onAddToPlaylist: (Track) -> Unit = {},
    onEditMetadata: (Track) -> Unit = {},
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Canciones que te gustan", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Todavía no diste Me gusta a ninguna canción",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Toca el icono de corazón en cualquier canción para guardarla aquí.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Button(
                        onClick = { onPlayTrack(tracks, 0) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reproducir favoritos (${tracks.size})")
                    }
                }

                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    PlaylistTrackRow(
                        track = track,
                        isCurrent = track.id == currentPlayingTrackId,
                        onPlay = { onPlayTrack(tracks, index) },
                        onToggleLike = { onToggleLike(track.id) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onEditMetadata = { onEditMetadata(track) },
                        onRemoveFromPlaylist = null,
                    )
                }
            }
        }
    }
}

/**
 * Pantalla de detalle de una playlist concreta con su lista de pistas y acciones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistName: String,
    tracks: List<Track>,
    currentPlayingTrackId: Long?,
    onBack: () -> Unit,
    onPlayTrack: (List<Track>, Int) -> Unit,
    onToggleLike: (Long) -> Unit,
    onRemoveFromPlaylist: (Long) -> Unit,
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onEditMetadata: (Track) -> Unit = {},
    deleteAnimationsEnabled: Boolean = true,
    accentArgb: Int = 0xFFA78BFA.toInt(),
    deleteEffectRenderer: DeleteEffectFrameRenderer? = null,
) {
    var trackPendingRemove by remember { mutableStateOf<Track?>(null) }
    val removingEffects = remember { mutableStateMapOf<Long, DeleteAnimationEffect>() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = playlistName,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Esta playlist está vacía. Usa el menú de cualquier canción en tu Biblioteca para añadirla aquí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Button(
                        onClick = { onPlayTrack(tracks, 0) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reproducir playlist (${tracks.size})")
                    }
                }

                itemsIndexed(tracks, key = { _, t -> t.id }) { index, track ->
                    RustPlaylistDeleteEffectHost(
                        trackId = track.id,
                        activeEffect = removingEffects[track.id],
                        accentArgb = accentArgb,
                        renderer = deleteEffectRenderer,
                        onAnimationFinished = {
                            removingEffects.remove(track.id)
                            onRemoveFromPlaylist(track.id)
                        },
                    ) {
                        PlaylistTrackRow(
                            track = track,
                            isCurrent = track.id == currentPlayingTrackId,
                            onPlay = { onPlayTrack(tracks, index) },
                            onToggleLike = { onToggleLike(track.id) },
                            onPlayNext = { onPlayNext(track) },
                            onAddToQueue = { onAddToQueue(track) },
                            onAddToPlaylist = null,
                            onEditMetadata = { onEditMetadata(track) },
                            onRemoveFromPlaylist = { trackPendingRemove = track },
                        )
                    }
                }
            }
        }
    }

    val pendingRemove = trackPendingRemove
    if (pendingRemove != null) {
        AlertDialog(
            onDismissRequest = { trackPendingRemove = null },
            title = { Text("Quitar de la playlist") },
            text = {
                Text(
                    "¿Seguro que quieres quitar \"${pendingRemove.title}\" de \"$playlistName\"? Seguirá disponible en tu Biblioteca.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = pendingRemove
                        trackPendingRemove = null
                        if (deleteAnimationsEnabled && deleteEffectRenderer != null) {
                            removingEffects[target.id] = DeleteAnimationEffect.random()
                        } else {
                            onRemoveFromPlaylist(target.id)
                        }
                    },
                ) {
                    Text("Quitar")
                }
            },
            dismissButton = {
                TextButton(onClick = { trackPendingRemove = null }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

/**
 * Popup / ModalBottomSheet para añadir una canción a una playlist existente o crear una nueva en el acto.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistBottomSheet(
    track: Track,
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
) {
    var showNewDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Añadir a playlist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = { showNewDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Crear nueva playlist")
            }

            Spacer(modifier = Modifier.height(8.dp))

            playlists.forEach { playlist ->
                ListItem(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectPlaylist(playlist.id) },
                    headlineContent = { Text(playlist.name, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text("${playlist.trackCount} ${if (playlist.trackCount == 1) "canción" else "canciones"}")
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
        }
    }

    if (showNewDialog) {
        CreatePlaylistDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { name ->
                showNewDialog = false
                onCreatePlaylistAndAdd(name)
            },
        )
    }
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nombre de la playlist") },
                placeholder = { Text("Ej. Nocturnas, Gym, Favoritas...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotEmpty(),
            ) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        },
    )
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val context = LocalContext.current
            val coverFile = playlist.coverFilePath?.let(::File)?.takeIf { it.exists() }
            if (coverFile != null) {
                val thumbRequest = remember(coverFile.absolutePath, coverFile.lastModified()) {
                    ImageRequest.Builder(context)
                        .data(coverFile)
                        .size(168)
                        .build()
                }
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${playlist.trackCount} ${if (playlist.trackCount == 1) "canción" else "canciones"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Eliminar playlist ${playlist.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtistPlaylistCard(
    artistPlaylist: ArtistPlaylist,
    onOpen: () -> Unit,
    onPlayOnlyArtist: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val context = LocalContext.current
            val coverFile = artistPlaylist.coverFilePath?.let(::File)?.takeIf { it.exists() }
            if (coverFile != null) {
                val thumbRequest = remember(coverFile.absolutePath, coverFile.lastModified()) {
                    ImageRequest.Builder(context)
                        .data(coverFile)
                        .size(168)
                        .build()
                }
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = artistPlaylist.artistName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artistPlaylist.artistName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${artistPlaylist.trackCount} ${if (artistPlaylist.trackCount == 1) "canción" else "canciones"} • ${formatArtistTotalDuration(artistPlaylist.totalDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FilledIconButton(
                onClick = onPlayOnlyArtist,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = "Reproducir canciones de ${artistPlaylist.artistName}",
                )
            }
        }
    }
}

@Composable
private fun PlaylistTrackRow(
    track: Track,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onEditMetadata: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)?,
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
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val context = LocalContext.current
            val coverFile = track.coverFilePath?.let(::File)?.takeIf { it.exists() }
            if (coverFile != null) {
                val thumbRequest = remember(coverFile.absolutePath, coverFile.lastModified()) {
                    ImageRequest.Builder(context)
                        .data(coverFile)
                        .size(168)
                        .build()
                }
                AsyncImage(
                    model = thumbRequest,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${track.artist} • ${formatDuration(track.durationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            IconButton(onClick = onToggleLike) {
                Icon(
                    imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Me gusta",
                    tint = if (track.isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Opciones de ${track.title}",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Reproducir siguiente") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPlayNext()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Añadir a la cola") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddToQueue()
                        },
                    )
                    if (onAddToPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Añadir a playlist") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onAddToPlaylist()
                            },
                        )
                    }
                    if (onEditMetadata != null) {
                        DropdownMenuItem(
                            text = { Text("Editar metadatos") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEditMetadata()
                            },
                        )
                    }
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Quitar de la playlist") },
                            leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRemoveFromPlaylist()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Captura la fila de canción dentro de la playlist y reproduce un efecto aleatorio en Rust
 * (cenizas, papel quemándose o aura extraña) antes de desvincularla de la lista.
 */
@Composable
private fun RustPlaylistDeleteEffectHost(
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
                    Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)
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
            val frameBitmapA = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val frameBitmapB = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}
