package com.auramusic.feature.effects

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.SurroundSound
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auramusic.core.model.AudioEffectPreset
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.PlaybackTuningHelper
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pantalla dedicada de efectos espaciales y de textura en tiempo real:
 * - Audio Espacial 8D psicoacústico (ITD + sombra de cabeza ILD + cross-feed en Rust DSP).
 * - Reverberación estéreo Freeverb (8 filtros comb + 4 allpass por canal en Rust DSP).
 * - Modo Slowed (velocidad + tono estilo vinilo/cinta + filtro de calidez analógica en Rust).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioEffectsScreen(
    effectsState: AudioEffectsState,
    onBack: () -> Unit,
    onSelectPreset: (AudioEffectPreset) -> Unit,
    onSetReverbEnabled: (Boolean) -> Unit,
    onSetReverbRoomSize: (Float) -> Unit,
    onSetReverbDamping: (Float) -> Unit,
    onSetReverbWetMix: (Float) -> Unit,
    onSetReverbWidth: (Float) -> Unit,
    onSetSlowedEnabled: (Boolean) -> Unit,
    onSetSlowedSpeed: (Float) -> Unit,
    onSetSlowedPreservePitch: (Boolean) -> Unit,
    onSetSlowedWarmth: (Float) -> Unit,
    onSetSpatial8dEnabled: (Boolean) -> Unit,
    onSetSpatial8dSpeed: (Float) -> Unit,
    onSetSpatial8dDepth: (Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activePreset = AudioEffectsState.findPresetById(effectsState.activePresetId)
    val activeTitle = when {
        !effectsState.isAnyEffectEnabled -> "Audio original (Bypass)"
        activePreset != null -> activePreset.displayName
        else -> buildList {
            if (effectsState.spatial8dEnabled) add("Audio 8D")
            if (effectsState.slowedEnabled) add("Slowed")
            if (effectsState.reverbEnabled) add("Reverb")
        }.joinToString(" + ") + " (Personalizado)"
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "8D, Reverb y Slowed",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Procesador DSP Rust en tiempo real",
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
                actions = {
                    IconButton(onClick = onReset) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Restablecer efectos",
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (effectsState.isAnyEffectEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
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
                            color = if (effectsState.isAnyEffectEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.SurroundSound,
                                    contentDescription = null,
                                    tint = if (effectsState.isAnyEffectEnabled) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = activePreset?.subtitle
                                    ?: (PlaybackTuningHelper.formatEffectsSummary(effectsState)
                                        ?: "Activa un preset o ajusta Audio 8D, Reverb y Slowed manualmente."),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (effectsState.isAnyEffectEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            item {
                AcousticDecayPreviewCard(effectsState = effectsState)
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Modos rápidos",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(AudioEffectsState.PRESETS, key = { it.id }) { preset ->
                            val isSelected = effectsState.activePresetId == preset.id
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { onSelectPreset(preset) },
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ) {
                                Text(
                                    text = preset.displayName,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // Tarjeta dedicada: Audio Espacial 8D en Rust (ITD + sombra craneal + órbita 360°)
            item {
                Spatial8dCard(
                    enabled = effectsState.spatial8dEnabled,
                    speed = effectsState.spatial8dSpeed,
                    depth = effectsState.spatial8dDepth,
                    onToggleEnabled = onSetSpatial8dEnabled,
                    onSpeedChange = onSetSpatial8dSpeed,
                    onDepthChange = onSetSpatial8dDepth,
                )
            }

            // Tarjeta dedicada: Modo Slowed (velocidad + tono grave + calidez Rust)
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SlowMotionVideo,
                                    contentDescription = null,
                                    tint = if (effectsState.slowedEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Efecto Slowed",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = if (effectsState.slowedEnabled) {
                                            "Ritmo ${PlaybackTuningHelper.formatSpeedLabel(effectsState.slowedSpeed)} • ${if (effectsState.slowedPreservePitch) "Tono original" else "Tono grave de cinta"}"
                                        } else {
                                            "Desactivado"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = effectsState.slowedEnabled,
                                onCheckedChange = onSetSlowedEnabled,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (effectsState.slowedEnabled) 1f else 0.55f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Velocidad Slowed",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = PlaybackTuningHelper.formatSpeedLabel(effectsState.slowedSpeed),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }

                            Slider(
                                value = effectsState.slowedSpeed,
                                enabled = effectsState.slowedEnabled,
                                onValueChange = { raw ->
                                    val snapped = (raw * 100f).roundToInt() / 100f
                                    onSetSlowedSpeed(snapped)
                                },
                                valueRange = AudioEffectsState.MIN_SLOWED_SPEED..AudioEffectsState.MAX_SLOWED_SPEED,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                AudioEffectsState.SLOWED_SPEED_PRESETS.forEach { presetSpeed ->
                                    val selected = kotlin.math.abs(effectsState.slowedSpeed - presetSpeed) < 0.015f
                                    if (selected) {
                                        FilledTonalButton(
                                            onClick = { onSetSlowedSpeed(presetSpeed) },
                                            enabled = effectsState.slowedEnabled,
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) {
                                            Text(
                                                text = PlaybackTuningHelper.formatSpeedLabel(presetSpeed),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                            )
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { onSetSlowedSpeed(presetSpeed) },
                                            enabled = effectsState.slowedEnabled,
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 4.dp),
                                        ) {
                                            Text(
                                                text = PlaybackTuningHelper.formatSpeedLabel(presetSpeed),
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Tono grave natural (estilo vinilo)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "Baja el tono junto con la velocidad para un Slowed auténtico.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = !effectsState.slowedPreservePitch,
                                    enabled = effectsState.slowedEnabled,
                                    onCheckedChange = { naturalPitch ->
                                        onSetSlowedPreservePitch(!naturalPitch)
                                    },
                                )
                            }

                            EffectPercentSlider(
                                label = "Calidez analógica (LPF Rust)",
                                value = effectsState.slowedWarmth,
                                enabled = effectsState.slowedEnabled,
                                onValueChange = onSetSlowedWarmth,
                            )
                        }
                    }
                }
            }

            // Tarjeta dedicada: Reverberación estéreo Freeverb en Rust
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = if (effectsState.reverbEnabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Reverb Estéreo (Rust)",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = if (effectsState.reverbEnabled) {
                                            "Sala ${(effectsState.reverbRoomSize * 100).roundToInt()}% • Mezcla ${(effectsState.reverbWetMix * 100).roundToInt()}%"
                                        } else {
                                            "Desactivado (8 combs + 4 allpass en espera)"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = effectsState.reverbEnabled,
                                onCheckedChange = onSetReverbEnabled,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (effectsState.reverbEnabled) 1f else 0.55f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EffectPercentSlider(
                                label = "Tamaño de sala",
                                value = effectsState.reverbRoomSize,
                                enabled = effectsState.reverbEnabled,
                                onValueChange = onSetReverbRoomSize,
                            )

                            EffectPercentSlider(
                                label = "Intensidad / Mezcla (Wet)",
                                value = effectsState.reverbWetMix,
                                enabled = effectsState.reverbEnabled,
                                onValueChange = onSetReverbWetMix,
                            )

                            EffectPercentSlider(
                                label = "Amortiguación de agudos",
                                value = effectsState.reverbDamping,
                                enabled = effectsState.reverbEnabled,
                                onValueChange = onSetReverbDamping,
                            )

                            EffectPercentSlider(
                                label = "Amplitud estéreo",
                                value = effectsState.reverbWidth,
                                enabled = effectsState.reverbEnabled,
                                onValueChange = onSetReverbWidth,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectPercentSlider(
    label: String,
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Slider(
            value = value.coerceIn(0f, 1f),
            enabled = enabled,
            onValueChange = { next ->
                val snapped = ((next * 100f).roundToInt() / 100f).coerceIn(0f, 1f)
                onValueChange(snapped)
            },
            valueRange = 0f..1f,
        )
    }
}

/**
 * Visualización de la envolvente acústica de reverberación y calidez según los parámetros activos.
 */
@Composable
private fun AcousticDecayPreviewCard(
    effectsState: AudioEffectsState,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val activeAlpha = if (effectsState.isAnyEffectEnabled) 1f else 0.45f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(106.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .alpha(activeAlpha),
        ) {
            val w = size.width
            val h = size.height
            val centerY = h * 0.5f

            drawLine(
                color = gridColor.copy(alpha = 0.55f),
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 1.2.dp.toPx(),
            )

            val room = if (effectsState.reverbEnabled) effectsState.reverbRoomSize else 0.08f
            val wet = if (effectsState.reverbEnabled) effectsState.reverbWetMix else 0.12f
            val damp = if (effectsState.reverbEnabled) effectsState.reverbDamping else 0.5f
            val slowStretch = if (effectsState.slowedEnabled) {
                (1.0f / effectsState.slowedSpeed).coerceIn(1.0f, 1.45f)
            } else {
                1.0f
            }

            val decayRate = (5.2f - room * 3.8f).coerceIn(1.0f, 5.5f)
            val freq = (26f - damp * 10f) / slowStretch
            val steps = 96

            val upperPath = Path()
            val fillPath = Path()
            for (i in 0..steps) {
                val t = i.toFloat() / steps.toFloat()
                val x = t * w
                val env = exp(-t * decayRate) * (0.28f + wet * 0.72f)
                val ripple = 0.65f + 0.35f * sin(t * freq * 3.14159f)
                val amp = (env * ripple).coerceIn(0f, 1f) * (h * 0.44f)
                val y = centerY - amp
                if (i == 0) {
                    upperPath.moveTo(x, y)
                    fillPath.moveTo(x, centerY)
                    fillPath.lineTo(x, y)
                } else {
                    upperPath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            for (i in steps downTo 0) {
                val t = i.toFloat() / steps.toFloat()
                val x = t * w
                val env = exp(-t * decayRate) * (0.28f + wet * 0.72f)
                val ripple = 0.65f + 0.35f * sin(t * freq * 3.14159f)
                val amp = (env * ripple).coerceIn(0f, 1f) * (h * 0.44f)
                fillPath.lineTo(x, centerY + amp)
            }
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.38f),
                        secondary.copy(alpha = 0.08f),
                    ),
                ),
            )
            drawPath(
                path = upperPath,
                color = primary,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * Tarjeta dedicada de Audio Espacial 8D con radar animado 360°, velocidad de giro
 * (en segundos por vuelta) e intensidad/profundidad psicoacústica.
 */
@Composable
private fun Spatial8dCard(
    enabled: Boolean,
    speed: Float,
    depth: Float,
    onToggleEnabled: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onDepthChange: (Float) -> Unit,
) {
    val orbitPeriodSec = PlaybackTuningHelper.spatial8dOrbitPeriodSeconds(speed)
    val orbitLabel = String.format(Locale.getDefault(), "%.1f s/vuelta", orbitPeriodSec)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val ringColor = MaterialTheme.colorScheme.outlineVariant

    var phaseRad by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, speed) {
        if (!enabled) return@LaunchedEffect
        var lastMs = 0L
        val omega = ((2.0 * PI) / orbitPeriodSec.toDouble()).toFloat()
        while (true) {
            withFrameMillis { frameMs ->
                if (lastMs != 0L) {
                    val dt = ((frameMs - lastMs).coerceIn(0L, 100L)) / 1000f
                    phaseRad = (phaseRad + omega * dt) % (2f * PI.toFloat())
                }
                lastMs = frameMs
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Headphones,
                        contentDescription = null,
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Audio 8D (Rust)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = if (enabled) {
                                "Órbita $orbitLabel • Intensidad ${(depth * 100f).roundToInt()}% • Usa audífonos"
                            } else {
                                "Desactivado • Recomendado usar audífonos"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.55f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Canvas(modifier = Modifier.size(72.dp)) {
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.5f
                    val maxRadius = size.minDimension * 0.42f
                    val activeRadius = maxRadius * (0.45f + depth.coerceIn(0f, 1f) * 0.55f)

                    drawCircle(
                        color = ringColor.copy(alpha = 0.45f),
                        radius = maxRadius,
                        center = Offset(cx, cy),
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                    drawCircle(
                        color = secondary.copy(alpha = 0.25f),
                        radius = maxRadius * 0.22f,
                        center = Offset(cx, cy),
                    )
                    drawCircle(
                        color = if (enabled) primary else ringColor,
                        radius = 4.dp.toPx(),
                        center = Offset(cx, cy),
                    )

                    val px = cx + activeRadius * sin(phaseRad)
                    val py = cy - activeRadius * cos(phaseRad)
                    if (enabled) {
                        drawCircle(
                            color = primary.copy(alpha = 0.28f),
                            radius = 10.dp.toPx(),
                            center = Offset(px, py),
                        )
                    }
                    drawCircle(
                        color = if (enabled) primary else ringColor,
                        radius = 5.5.dp.toPx(),
                        center = Offset(px, py),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Velocidad de giro",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = orbitLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = speed.coerceIn(0f, 1f),
                        enabled = enabled,
                        onValueChange = { next ->
                            val snapped = ((next * 100f).roundToInt() / 100f).coerceIn(0f, 1f)
                            onSpeedChange(snapped)
                        },
                        valueRange = 0f..1f,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.55f),
            ) {
                EffectPercentSlider(
                    label = "Profundidad / Apertura 360°",
                    value = depth,
                    enabled = enabled,
                    onValueChange = onDepthChange,
                )
            }
        }
    }
}
