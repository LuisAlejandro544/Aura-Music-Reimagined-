package com.auramusic.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auramusic.core.model.Playlist
import com.auramusic.core.model.Track
import com.auramusic.core.model.TrackMetadataHelper
import com.auramusic.feature.playlists.AddToPlaylistBottomSheet

/**
 * Contenedor modular de modales transversales compartidos por las distintas pantallas:
 * hoja para añadir una canción a playlists y diálogo de edición local de metadatos.
 */
@Composable
internal fun AuraTrackModalHost(
    trackForPlaylistSheet: Track?,
    trackPendingMetadataEdit: Track?,
    playlists: List<Playlist>,
    onDismissPlaylistSheet: () -> Unit,
    onAddTrackToPlaylist: (playlistId: Long, trackId: Long) -> Unit,
    onCreatePlaylistAndAddTrack: (name: String, trackId: Long) -> Unit,
    onDismissMetadataEdit: () -> Unit,
    onSaveTrackMetadata: (trackId: Long, title: String, artist: String) -> Unit,
) {
    if (trackForPlaylistSheet != null) {
        AddToPlaylistBottomSheet(
            track = trackForPlaylistSheet,
            playlists = playlists,
            onDismiss = onDismissPlaylistSheet,
            onSelectPlaylist = { playlistId ->
                onAddTrackToPlaylist(playlistId, trackForPlaylistSheet.id)
                onDismissPlaylistSheet()
            },
            onCreatePlaylistAndAdd = { name ->
                onCreatePlaylistAndAddTrack(name, trackForPlaylistSheet.id)
                onDismissPlaylistSheet()
            },
        )
    }

    if (trackPendingMetadataEdit != null) {
        EditTrackMetadataDialog(
            track = trackPendingMetadataEdit,
            onDismiss = onDismissMetadataEdit,
            onSave = { newTitle, newArtist ->
                onSaveTrackMetadata(trackPendingMetadataEdit.id, newTitle, newArtist)
                onDismissMetadataEdit()
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
                    text = "Si dejas el artista vacío se guardará como " +
                        "«${TrackMetadataHelper.DEFAULT_ARTIST}». " +
                        "Los cambios actualizan al instante la biblioteca, artistas y notificación.",
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
