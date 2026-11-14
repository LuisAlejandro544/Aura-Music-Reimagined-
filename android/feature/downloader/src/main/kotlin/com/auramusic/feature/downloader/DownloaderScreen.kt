package com.auramusic.feature.downloader

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.Track
import java.util.Locale

/**
 * Pantalla dedicada para descargar solo audio mediante enlaces compatibles con `yt-dlp`.
 *
 * Arquitectura empleada (sin WebView ni FFmpeg):
 * - Intérprete ligero Python 3.11 (Chaquopy, compilado a `.pyc`).
 * - Paquetes `yt-dlp` y `yt-dlp-ejs` con auto-actualización desde PyPI + SHA-256.
 * - Runtime JavaScript `QuickJS-NG` (`libaura_qjs.so`) compilado en Rust para 32 y 64 bits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    downloadState: DownloadState,
    engineStatus: DownloaderEngineStatus,
    onBack: () -> Unit,
    onStartDownload: (String) -> Unit,
    onPlayDownloadedTrack: (Track) -> Unit,
    onCheckUpdates: () -> Unit,
    onToggleAutoUpdate: (Boolean) -> Unit,
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
                                text = "Descarga flujos M4A/Opus listos para ExoPlayer y Rust (lofty), sin WebView ni FFmpeg.",
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
                            onValueChange = { urlInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !downloadState.isBusy,
                            label = { Text("URL de YouTube o enlace compatible") },
                            placeholder = { Text("https://www.youtube.com/watch?v=...") },
                            leadingIcon = {
                                Icon(Icons.Rounded.Link, contentDescription = null)
                            },
                            trailingIcon = {
                                if (urlInput.isNotEmpty() && !downloadState.isBusy) {
                                    IconButton(onClick = { urlInput = "" }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Limpiar enlace",
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val pasted = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (pasted.isNotEmpty()) {
                                        urlInput = pasted
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
                                onClick = { onStartDownload(urlInput) },
                                enabled = !downloadState.isBusy && urlInput.isNotBlank(),
                                modifier = Modifier.weight(1.3f),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (downloadState.isBusy) "Procesando..." else "Descargar")
                            }
                        }
                    }
                }
            }

            if (downloadState.status != DownloadStatus.IDLE) {
                item {
                    DownloadStatusCard(
                        downloadState = downloadState,
                        onPlayDownloadedTrack = onPlayDownloadedTrack,
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
private fun DownloadStatusCard(
    downloadState: DownloadState,
    onPlayDownloadedTrack: (Track) -> Unit,
    onDismiss: () -> Unit,
) {
    val containerColor = when (downloadState.status) {
        DownloadStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = when (downloadState.status) {
        DownloadStatus.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val statusTitle = when (downloadState.status) {
        DownloadStatus.IDLE -> ""
        DownloadStatus.CHECKING_UPDATES -> "Comprobando actualizaciones..."
        DownloadStatus.RESOLVING -> "Resolviendo enlace con QuickJS-NG..."
        DownloadStatus.DOWNLOADING -> "Descargando flujo de audio"
        DownloadStatus.IMPORTING -> "Importando a Aura Music..."
        DownloadStatus.COMPLETED -> "Descarga completada"
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
                val bytesSummary = buildBytesLabel(
                    downloaded = downloadState.downloadedBytes,
                    total = downloadState.totalBytes,
                    percent = downloadState.progressPercent,
                )
                Text(
                    text = bytesSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.85f),
                )
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

private fun buildBytesLabel(downloaded: Long, total: Long, percent: Float): String {
    val pct = percent.coerceIn(0f, 100f)
    val dlMb = downloaded / (1024f * 1024f)
    return if (total > 0L) {
        val totalMb = total / (1024f * 1024f)
        String.format(Locale.US, "%.1f%% • %.1f MB / %.1f MB", pct, dlMb, totalMb)
    } else {
        String.format(Locale.US, "%.1f%% • %.1f MB", pct, dlMb)
    }
}
