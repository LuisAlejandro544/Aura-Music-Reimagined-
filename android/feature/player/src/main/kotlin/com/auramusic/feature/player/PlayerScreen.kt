package com.auramusic.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.auramusic.core.model.PlaybackState
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.model.PlaybackTuningHelper
import com.auramusic.core.model.RepeatMode
import com.auramusic.core.model.Track
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Mini reproductor persistente anclado sobre la barra de navegación inferior.
 * Muestra la carátula cuadrada normal, título, artista, botón de Me gusta, Play/Pausa y Siguiente,
 * adaptando sus colores con animaciones suaves a la carátula activa si el usuario lo habilita.
 */
@Composable
fun MiniPlayerBar(
    playbackState: PlaybackState,
    dynamicCoverColorsEnabled: Boolean = true,
    onOpenFullPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleLike: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = playbackState.currentTrack ?: return

    CoverDynamicTheme(
        coverPath = track.coverFilePath,
        enabled = dynamicCoverColorsEnabled,
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clickable(onClick = onOpenFullPlayer),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            ),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SquareCoverArt(
                    coverPath = track.coverFilePath,
                    contentDescription = track.title,
                    modifier = Modifier.size(48.dp),
                    cornerRadiusDp = 10,
                )

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
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = { onToggleLike(track.id) }) {
                    Icon(
                        imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (track.isLiked) "Quitar me gusta" else "Me gusta",
                        tint = if (track.isLiked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                    )
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente canción",
                    )
                }
            }

            LinearProgressIndicator(
                progress = { playbackState.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            }
        }
    }
}

/**
 * Pantalla completa del reproductor de Aura Music.
 * Diseñada con controles claros y carátula cuadrada clásica (sin diseños rebuscados ni confusos),
 * adaptando sus acentos y fondo animado a la carátula activa cuando está habilitado.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    playbackState: PlaybackState,
    playbackTuning: PlaybackTuning = PlaybackTuning(),
    dynamicCoverColorsEnabled: Boolean = true,
    onCollapse: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenAddToPlaylist: (Track) -> Unit,
    onChangeCover: (Track) -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenQueue: () -> Unit = {},
    onSetPlaybackSpeed: (Float) -> Unit = {},
    onSetCrossfadeEnabled: (Boolean) -> Unit = {},
    onSetCrossfadeSeconds: (Int) -> Unit = {},
) {
    val track = playbackState.currentTrack
    var showTuningSheet by remember { mutableStateOf(false) }

    CoverDynamicTheme(
        coverPath = track?.coverFilePath,
        enabled = dynamicCoverColorsEnabled,
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "REPRODUCIENDO AHORA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = track?.album ?: "Aura Music",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Minimizar reproductor",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showTuningSheet = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = "Velocidad y Crossfade",
                            tint = if (
                                kotlin.math.abs(playbackTuning.playbackSpeed - 1.0f) > 0.01f ||
                                playbackTuning.crossfadeEnabled
                            ) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    IconButton(onClick = onOpenQueue) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = "Cola de reproducción",
                        )
                    }
                    IconButton(onClick = onOpenEqualizer) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = "Ecualizador",
                        )
                    }
                    if (track != null) {
                        IconButton(onClick = { onChangeCover(track) }) {
                            Icon(
                                imageVector = Icons.Rounded.Image,
                                contentDescription = "Cambiar carátula",
                            )
                        }
                        IconButton(onClick = { onOpenAddToPlaylist(track) }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                                contentDescription = "Añadir a playlist",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        if (track == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Selecciona una canción para empezar a escuchar",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val backdropTint = LocalCoverBackdropTint.current
        val bgGradient = Brush.verticalGradient(
            colors = listOf(
                backdropTint.copy(alpha = 0.48f),
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
                MaterialTheme.colorScheme.background,
            ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgGradient)
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Carátula cuadrada clásica bien enmarcada y proporcionada.
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val squareSize = minOf(maxWidth, maxHeight).coerceAtMost(360.dp)
                Surface(
                    modifier = Modifier.size(squareSize),
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
                    ),
                    tonalElevation = 6.dp,
                    shadowElevation = 14.dp,
                ) {
                    SquareCoverArt(
                        coverPath = track.coverFilePath,
                        contentDescription = "Carátula de ${track.title}",
                        modifier = Modifier.fillMaxSize(),
                        cornerRadiusDp = 22,
                    )
                }
            }

            // Bloque de información + corazón de Me gusta.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(
                    onClick = { onToggleLike(track.id) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (track.isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (track.isLiked) "Quitar me gusta" else "Me gusta",
                        tint = if (track.isLiked) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // Barra de progreso interactiva con tiempos formateados.
            var isDragging by remember { mutableStateOf(false) }
            var dragPosition by remember { mutableFloatStateOf(0f) }
            val durationFloat = playbackState.durationMs.coerceAtLeast(1L).toFloat()
            val sliderValue = if (isDragging) {
                dragPosition
            } else {
                playbackState.positionMs.toFloat().coerceIn(0f, durationFloat)
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = sliderValue,
                    onValueChange = { value ->
                        isDragging = true
                        dragPosition = value
                    },
                    onValueChangeFinished = {
                        onSeekTo(dragPosition.toLong())
                        isDragging = false
                    },
                    valueRange = 0f..durationFloat,
                    thumb = {
                        val thumbDiameter = if (isDragging) 20.dp else 16.dp
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(thumbDiameter)
                                    .shadow(elevation = 4.dp, shape = CircleShape)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    },
                    track = { sliderState ->
                        val range = (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                            .coerceAtLeast(1f)
                        val fraction = ((sliderState.value - sliderState.valueRange.start) / range)
                            .coerceIn(0f, 1f)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatPlaybackTime(sliderValue.toLong()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (kotlin.math.abs(playbackTuning.playbackSpeed - 1.0f) > 0.01f ||
                        playbackTuning.crossfadeEnabled
                    ) {
                        val activeSummary = buildList {
                            if (kotlin.math.abs(playbackTuning.playbackSpeed - 1.0f) > 0.01f) {
                                add(PlaybackTuningHelper.formatSpeedLabel(playbackTuning.playbackSpeed))
                            }
                            if (playbackTuning.crossfadeEnabled) {
                                add("Crossfade ${playbackTuning.crossfadeSeconds}s")
                            }
                        }.joinToString(" • ")
                        Text(
                            text = activeSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { showTuningSheet = true },
                        )
                    }
                    Text(
                        text = formatPlaybackTime(playbackState.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Controles clásicos del reproductor.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Aleatorio",
                        tint = if (playbackState.shuffleEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                IconButton(
                    onClick = onSkipPrevious,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        modifier = Modifier.size(36.dp),
                    )
                }

                FilledIconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
                        modifier = Modifier.size(38.dp),
                    )
                }

                IconButton(
                    onClick = onSkipNext,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Siguiente",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (playbackState.repeatMode == RepeatMode.ONE) {
                            Icons.Rounded.RepeatOne
                        } else {
                            Icons.Rounded.Repeat
                        },
                        contentDescription = "Modo de repetición",
                        tint = if (playbackState.repeatMode != RepeatMode.OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        }

        if (showTuningSheet) {
            PlaybackTuningBottomSheet(
                playbackTuning = playbackTuning,
                onDismiss = { showTuningSheet = false },
                onSetPlaybackSpeed = onSetPlaybackSpeed,
                onSetCrossfadeEnabled = onSetCrossfadeEnabled,
                onSetCrossfadeSeconds = onSetCrossfadeSeconds,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackTuningBottomSheet(
    playbackTuning: PlaybackTuning,
    onDismiss: () -> Unit,
    onSetPlaybackSpeed: (Float) -> Unit,
    onSetCrossfadeEnabled: (Boolean) -> Unit,
    onSetCrossfadeSeconds: (Int) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Velocidad y Crossfade",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Ajusta el ritmo y el fundido entre pistas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onSetPlaybackSpeed(PlaybackTuning.DEFAULT_PLAYBACK_SPEED) },
                ) {
                    Text("Normal 1.0x")
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Velocidad de reproducción",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = PlaybackTuningHelper.formatSpeedLabel(playbackTuning.playbackSpeed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Slider(
                        value = playbackTuning.playbackSpeed,
                        onValueChange = { raw ->
                            val snapped = ((raw * 20f).roundToInt() / 20f)
                            onSetPlaybackSpeed(snapped)
                        },
                        valueRange = PlaybackTuning.MIN_PLAYBACK_SPEED..PlaybackTuning.MAX_PLAYBACK_SPEED,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PlaybackTuning.SPEED_PRESETS.forEach { preset ->
                            val selected = kotlin.math.abs(playbackTuning.playbackSpeed - preset) < 0.02f
                            if (selected) {
                                FilledTonalButton(
                                    onClick = { onSetPlaybackSpeed(preset) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                                ) {
                                    Text(
                                        text = PlaybackTuningHelper.formatSpeedLabel(preset),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onSetPlaybackSpeed(preset) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
                                ) {
                                    Text(
                                        text = PlaybackTuningHelper.formatSpeedLabel(preset),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Crossfade entre canciones",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (playbackTuning.crossfadeEnabled) {
                                    "Empieza ${playbackTuning.crossfadeSeconds} s antes de terminar"
                                } else {
                                    "Desactivado • Cambio normal entre pistas"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (playbackTuning.crossfadeEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Switch(
                            checked = playbackTuning.crossfadeEnabled,
                            onCheckedChange = onSetCrossfadeEnabled,
                        )
                    }

                    Text(
                        text = "Inicio del crossfade: ${playbackTuning.crossfadeSeconds} s antes del final",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Slider(
                        value = playbackTuning.crossfadeSeconds.toFloat(),
                        onValueChange = { value -> onSetCrossfadeSeconds(value.roundToInt()) },
                        valueRange = PlaybackTuning.MIN_CROSSFADE_SECONDS.toFloat()..PlaybackTuning.MAX_CROSSFADE_SECONDS.toFloat(),
                        steps = PlaybackTuning.MAX_CROSSFADE_SECONDS - PlaybackTuning.MIN_CROSSFADE_SECONDS - 1,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${PlaybackTuning.MIN_CROSSFADE_SECONDS} s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${PlaybackTuning.MAX_CROSSFADE_SECONDS} s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Renderiza la carátula cuadrada de una canción desde disco (embebida o procedural Rust).
 */
@Composable
fun SquareCoverArt(
    coverPath: String?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    cornerRadiusDp: Int = 12,
) {
    val shape = RoundedCornerShape(cornerRadiusDp.dp)
    val file = coverPath?.let(::File)?.takeIf { it.exists() }

    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High,
            modifier = modifier.clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

fun formatPlaybackTime(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
}
