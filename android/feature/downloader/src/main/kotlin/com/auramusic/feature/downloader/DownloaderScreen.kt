package com.auramusic.feature.downloader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.DownloadAudioFormatOption
import com.auramusic.core.model.DownloadPreview
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.Track
import java.io.File
import java.util.Locale

/**
 * Pantalla dedicada para descargar solo audio mediante enlaces compatibles con `yt-dlp`.
 *
 * Flujo en dos pasos:
 * 1. Analiza el enlace para mostrar miniatura, título, artista/canal, duración y peso estimado.
 * 2. Al confirmar el usuario, descarga el flujo de audio mostrando peso total, bytes descargados
 *    y velocidad de internet en tiempo real, integrándolo automáticamente en Room con Rust (`lofty`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    downloadState: DownloadState,
    engineStatus: DownloaderEngineStatus,
    onBack: () -> Unit,
    onInspectUrl: (String) -> Unit,
    onConfirmDownload: () -> Unit,
    onCancelPreview: () -> Unit,
    onCancelDownload: () -> Unit = {},
    onPlayDownloadedTrack: (Track) -> Unit,
    onCheckUpdates: () -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onSelectQualityPreset: (AudioDownloadQuality) -> Unit = {},
    onSelectPreviewFormat: (String) -> Unit = {},
    onDismissDownloadStatus: () -> Unit,
    onDismissUpdateMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var urlInput by rememberSaveable { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Descargar audio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "yt-dlp • yt-dlp-ejs • QuickJS-NG (Rust)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Volver",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Extracción directa de audio",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "Analiza el enlace antes de descargar. Al confirmar, la descarga sigue en segundo plano con notificación incluso si sales de Aura Music.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Enlace de audio",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { next ->
                                urlInput = next
                                if (downloadState.status == DownloadStatus.PREVIEW_READY) {
                                    onCancelPreview()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !downloadState.isBusy,
                            label = { Text("URL de YouTube o enlace compatible") },
                            placeholder = { Text("https://www.youtube.com/watch?v=...") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Link, contentDescription = null)
                            },
                            trailingIcon = {
                                if (urlInput.isNotEmpty() && !downloadState.isBusy) {
                                    IconButton(
                                        onClick = {
                                            urlInput = ""
                                            if (downloadState.status == DownloadStatus.PREVIEW_READY) {
                                                onCancelPreview()
                                            }
                                        },
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Limpiar enlace",
                                        )
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (!downloadState.isBusy && urlInput.isNotBlank()) {
                                        onInspectUrl(urlInput)
                                    }
                                },
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Calidad de audio preferida",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AudioDownloadQuality.entries.forEach { quality ->
                                    val selected = engineStatus.preferredQuality == quality
                                    FilterChip(
                                        selected = selected,
                                        onClick = { onSelectQualityPreset(quality) },
                                        enabled = !downloadState.isBusy,
                                        modifier = Modifier.weight(1f),
                                        label = {
                                            Text(
                                                text = quality.shortLabel,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                }
                            }
                            Text(
                                text = engineStatus.preferredQuality.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val pasted = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (pasted.isNotEmpty()) {
                                        urlInput = pasted
                                        if (downloadState.status == DownloadStatus.PREVIEW_READY) {
                                            onCancelPreview()
                                        }
                                    }
                                },
                                enabled = !downloadState.isBusy,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pegar")
                            }

                            Button(
                                onClick = { onInspectUrl(urlInput) },
                                enabled = !downloadState.isBusy && urlInput.isNotBlank(),
                                modifier = Modifier.weight(1.35f),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (downloadState.isBusy) "Procesando..." else "Analizar enlace")
                            }
                        }
                    }
                }
            }

            val preview = downloadState.preview
            if (downloadState.status == DownloadStatus.PREVIEW_READY && preview != null) {
                item {
                    DownloadPreviewCard(
                        preview = preview,
                        onSelectFormat = onSelectPreviewFormat,
                        onConfirmDownload = onConfirmDownload,
                        onCancel = onCancelPreview,
                    )
                }
            } else if (downloadState.status != DownloadStatus.IDLE) {
                item {
                    DownloadStatusCard(
                        downloadState = downloadState,
                        onPlayDownloadedTrack = onPlayDownloadedTrack,
                        onCancelDownload = onCancelDownload,
                        onDismiss = onDismissDownloadStatus,
                    )
                }
            }

            item {
                EngineUpdaterCard(
                    engineStatus = engineStatus,
                    isDownloadBusy = downloadState.isBusy,
                    onCheckUpdates = onCheckUpdates,
                    onToggleAutoUpdate = onToggleAutoUpdate,
                    onDismissUpdateMessage = onDismissUpdateMessage,
                )
            }
        }
    }
}

@Composable
private fun DownloadPreviewCard(
    preview: DownloadPreview,
    onSelectFormat: (String) -> Unit,
    onConfirmDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Confirmar descarga de audio",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            val thumbFile = preview.localThumbnailPath?.let(::File)?.takeIf { it.exists() }
            if (thumbFile != null) {
                AsyncImage(
                    model = thumbFile,
                    contentDescription = preview.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = preview.channelOrArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PreviewMetaBadge(
                    icon = Icons.Rounded.Schedule,
                    label = "Duración",
                    value = formatPreviewDuration(preview.durationMs),
                    modifier = Modifier.weight(1f),
                )
                PreviewMetaBadge(
                    icon = Icons.Rounded.DataUsage,
                    label = "Peso aprox.",
                    value = if (preview.estimatedSizeBytes > 0L) {
                        formatByteSize(preview.estimatedSizeBytes)
                    } else {
                        "Variable"
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            if (preview.audioFormatLabel.isNotBlank()) {
                PreviewMetaBadge(
                    icon = Icons.Rounded.GraphicEq,
                    label = "Formato de audio",
                    value = preview.audioFormatLabel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (preview.availableFormats.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Elige calidad del formato",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    preview.availableFormats.forEach { option ->
                        val isSelected = preview.selectedFormatId == option.formatId
                        FormatOptionSelectorRow(
                            option = option,
                            selected = isSelected,
                            onClick = { onSelectFormat(option.formatId) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Cancelar")
                }

                Button(
                    onClick = onConfirmDownload,
                    modifier = Modifier.weight(1.4f),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Confirmar descarga")
                }
            }
        }
    }
}

@Composable
private fun FormatOptionSelectorRow(
    option: DownloadAudioFormatOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val displayLabel = option.label.ifBlank {
        buildString {
            append(option.extension.uppercase())
            if (option.codec.isNotBlank()) append(" (${option.codec})")
            if (option.bitrateKbps > 0) append(" • ${option.bitrateKbps} kbps")
        }
    }
    val sizeText = if (option.estimatedSizeBytes > 0L) {
        formatByteSize(option.estimatedSizeBytes)
    } else {
        "Variable"
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = null,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = displayLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = sizeText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun PreviewMetaBadge(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DownloadStatusCard(
    downloadState: DownloadState,
    onPlayDownloadedTrack: (Track) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val containerColor = when (downloadState.status) {
        DownloadStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        DownloadStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (downloadState.status) {
        DownloadStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val statusTitle = when (downloadState.status) {
        DownloadStatus.IDLE, DownloadStatus.PREVIEW_READY -> ""
        DownloadStatus.CHECKING_UPDATES -> "Comprobando actualizaciones..."
        DownloadStatus.FETCHING_INFO -> "Analizando información del vídeo..."
        DownloadStatus.RESOLVING -> "Resolviendo enlace con QuickJS-NG..."
        DownloadStatus.DOWNLOADING -> "Descargando en segundo plano"
        DownloadStatus.IMPORTING -> "Importando a Aura Music..."
        DownloadStatus.COMPLETED -> "Descarga completada"
        DownloadStatus.CANCELLED -> "Descarga cancelada"
        DownloadStatus.ERROR -> "Error en la descarga"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = when (downloadState.status) {
                    DownloadStatus.ERROR -> Icons.Rounded.ErrorOutline
                    DownloadStatus.COMPLETED -> Icons.Rounded.CheckCircle
                    DownloadStatus.CANCELLED -> Icons.Rounded.Close
                    else -> Icons.Rounded.CloudDownload
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                    if (!downloadState.title.isNullOrBlank()) {
                        Text(
                            text = downloadState.title.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (!downloadState.isBusy) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cerrar estado",
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (downloadState.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { downloadState.normalizedProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DownloadMetricTile(
                        icon = Icons.Rounded.DataUsage,
                        label = "Progreso / Peso",
                        value = buildBytesLabel(
                            downloaded = downloadState.downloadedBytes,
                            total = downloadState.totalBytes,
                            percent = downloadState.progressPercent,
                        ),
                        modifier = Modifier.weight(1.35f),
                    )
                    DownloadMetricTile(
                        icon = Icons.Rounded.Speed,
                        label = "Velocidad",
                        value = formatSpeedLabel(downloadState.speedBytesPerSec),
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (downloadState.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            val detailMessage = downloadState.errorMessage ?: downloadState.statusMessage
            if (!detailMessage.isNullOrBlank()) {
                Text(
                    text = detailMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.9f),
                )
            }

            if (downloadState.status == DownloadStatus.RESOLVING ||
                downloadState.status == DownloadStatus.DOWNLOADING
            ) {
                OutlinedButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Cancelar descarga")
                }
            }

            val completedTrack = downloadState.lastCompletedTrack
            if (downloadState.status == DownloadStatus.COMPLETED && completedTrack != null) {
                Button(
                    onClick = { onPlayDownloadedTrack(completedTrack) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reproducir ahora")
                }
            }
        }
    }
}

@Composable
private fun DownloadMetricTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EngineUpdaterCard(
    engineStatus: DownloaderEngineStatus,
    isDownloadBusy: Boolean,
    onCheckUpdates: () -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
    onDismissUpdateMessage: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Motor y auto-actualización",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Actualiza yt-dlp y yt-dlp-ejs directo desde PyPI sin recompilar el APK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            EngineVersionRow("Intérprete Python", engineStatus.pythonVersion)
            EngineVersionRow(
                label = "yt-dlp",
                value = buildVersionWithDate(engineStatus.ytDlpVersion, engineStatus.ytDlpUpdatedAt),
            )
            EngineVersionRow(
                label = "yt-dlp-ejs",
                value = buildVersionWithDate(engineStatus.ytDlpEjsVersion, engineStatus.ytDlpEjsUpdatedAt),
            )
            EngineVersionRow("Runtime JS (Rust)", engineStatus.quickJsVersion)

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-actualizar al descargar",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Verifica parches cada 12 horas automáticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = engineStatus.autoUpdateEnabled,
                    onCheckedChange = onToggleAutoUpdate,
                    enabled = !engineStatus.isUpdating && !isDownloadBusy,
                )
            }

            FilledTonalButton(
                onClick = onCheckUpdates,
                enabled = !engineStatus.isUpdating && !isDownloadBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (engineStatus.isUpdating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Actualizando desde PyPI...")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdateAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Buscar y actualizar ahora")
                }
            }

            if (!engineStatus.updateMessage.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = engineStatus.updateMessage.orEmpty(),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(
                            onClick = onDismissUpdateMessage,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Cerrar aviso",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineVersionRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildVersionWithDate(version: String, updatedAt: String?): String {
    return if (updatedAt.isNullOrBlank()) {
        version
    } else {
        "$version ($updatedAt)"
    }
}

private fun formatPreviewDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "Desconocida"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun formatByteSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    return when {
        safe >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", safe / (1024f * 1024f))
        safe >= 1024L -> String.format(Locale.US, "%.0f KB", safe / 1024f)
        else -> "$safe B"
    }
}

private fun buildBytesLabel(downloaded: Long, total: Long, percent: Float): String {
    val pct = percent.coerceIn(0f, 100f)
    val dlLabel = formatByteSize(downloaded)
    return if (total > 0L) {
        val totalLabel = formatByteSize(total)
        String.format(Locale.US, "%s / %s (%.1f%%)", dlLabel, totalLabel, pct)
    } else {
        "$dlLabel descargados"
    }
}

private fun formatSpeedLabel(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "Calculando..."
    return when {
        bytesPerSec >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024f * 1024f))
        bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024f)
        else -> "$bytesPerSec B/s"
    }
}
