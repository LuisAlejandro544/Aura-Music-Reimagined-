package com.auramusic.feature.equalizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auramusic.core.model.EqualizerPreset
import com.auramusic.core.model.EqualizerState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pantalla dedicada del ecualizador gráfico de 10 bandas (31 Hz - 16 kHz)
 * conectado en tiempo real al motor DSP nativo en Rust (`aura_core::equalizer`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    equalizerState: EqualizerState,
    onBack: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onBandGainChange: (Int, Float) -> Unit,
    onSelectPreset: (EqualizerPreset) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activePresetName = EqualizerState.findPresetById(equalizerState.activePresetId)?.displayName
        ?: "Personalizado"

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Ecualizador",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "10 bandas • Motor DSP Rust",
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
                            contentDescription = "Restablecer a plano",
                        )
                    }
                    Switch(
                        checked = equalizerState.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.padding(end = 12.dp),
                    )
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (equalizerState.isEnabled) {
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
                            color = if (equalizerState.isEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = null,
                                    tint = if (equalizerState.isEnabled) {
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
                                text = if (equalizerState.isEnabled) {
                                    "Ecualizador activo • $activePresetName"
                                } else {
                                    "Ecualizador desactivado (Bypass)"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Filtros biquad IIR de 64 bits en tiempo real sobre ExoPlayer.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (equalizerState.isEnabled) {
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
                EqualizerResponseCurveCard(
                    equalizerState = equalizerState,
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Perfiles de sonido",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(EqualizerState.PRESETS, key = { it.id }) { preset ->
                            val isSelected = equalizerState.activePresetId == preset.id
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

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (equalizerState.isEnabled) 1f else 0.58f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Consola de 10 bandas (-12 dB a +12 dB)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Doble toque = 0 dB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (bandIndex in 0 until EqualizerState.BAND_COUNT) {
                                val gain = equalizerState.bandGainsDb.getOrElse(bandIndex) { 0f }
                                VerticalBandFader(
                                    label = EqualizerState.BAND_SHORT_LABELS[bandIndex],
                                    gainDb = gain,
                                    enabled = equalizerState.isEnabled,
                                    onGainChange = { newGain ->
                                        onBandGainChange(bandIndex, newGain)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EqualizerResponseCurveCard(
    equalizerState: EqualizerState,
) {
    val primary = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val activeAlpha = if (equalizerState.isEnabled) 1f else 0.45f

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .alpha(activeAlpha),
        ) {
            val w = size.width
            val h = size.height
            val centerY = h * 0.5f

            // Líneas de referencia (+12 dB, 0 dB, -12 dB)
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(0f, 0f),
                end = Offset(w, 0f),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 1.2.dp.toPx(),
            )
            drawLine(
                color = gridColor.copy(alpha = 0.5f),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1.dp.toPx(),
            )

            val points = (0 until EqualizerState.BAND_COUNT).map { idx ->
                val x = (idx.toFloat() / (EqualizerState.BAND_COUNT - 1).toFloat()) * w
                val gain = equalizerState.bandGainsDb.getOrElse(idx) { 0f }
                val normalized = (gain / EqualizerState.MAX_GAIN_DB).coerceIn(-1f, 1f)
                val y = centerY - normalized * (h * 0.46f)
                Offset(x, y)
            }

            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.lastIndex) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val midX = (p0.x + p1.x) * 0.5f
                    cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                }
            }

            val fillPath = Path().apply {
                addPath(linePath)
                lineTo(points.last().x, h)
                lineTo(points.first().x, h)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.34f),
                        primary.copy(alpha = 0.02f),
                    ),
                ),
            )

            drawPath(
                path = linePath,
                color = primary,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )

            points.forEach { pt ->
                drawCircle(
                    color = primary,
                    radius = 4.dp.toPx(),
                    center = pt,
                )
            }
        }
    }
}

@Composable
private fun VerticalBandFader(
    label: String,
    gainDb: Float,
    enabled: Boolean,
    onGainChange: (Float) -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val zeroLineColor = MaterialTheme.colorScheme.outline
    val thumbBorderColor = MaterialTheme.colorScheme.background

    Column(
        modifier = Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatGainDb(gainDb),
            style = MaterialTheme.typography.labelSmall,
            color = if (gainDb != 0f && enabled) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .width(44.dp)
                .height(190.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onDoubleTap = { onGainChange(0f) },
                        onTap = { offset ->
                            val newGain = yOffsetToGainDb(offset.y, size.height.toFloat())
                            onGainChange(newGain)
                        },
                    )
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        val newGain = yOffsetToGainDb(change.position.y, size.height.toFloat())
                        onGainChange(newGain)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackW = 6.dp.toPx()
                val padY = 12.dp.toPx()
                val usableH = (size.height - padY * 2f).coerceAtLeast(1f)
                val centerX = size.width * 0.5f
                val centerY = padY + usableH * 0.5f

                // Pista vertical completa
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(centerX - trackW * 0.5f, padY),
                    size = Size(trackW, usableH),
                    cornerRadius = CornerRadius(trackW * 0.5f, trackW * 0.5f),
                )

                // Marca central 0 dB
                drawLine(
                    color = zeroLineColor,
                    start = Offset(centerX - 11.dp.toPx(), centerY),
                    end = Offset(centerX + 11.dp.toPx(), centerY),
                    strokeWidth = 1.5.dp.toPx(),
                )

                val normalized = (gainDb / EqualizerState.MAX_GAIN_DB).coerceIn(-1f, 1f)
                val thumbY = centerY - normalized * (usableH * 0.5f)
                val activeTop = minOf(centerY, thumbY)
                val activeHeight = kotlin.math.abs(thumbY - centerY)

                if (activeHeight > 1f) {
                    drawRoundRect(
                        color = primary,
                        topLeft = Offset(centerX - trackW * 0.5f, activeTop),
                        size = Size(trackW, activeHeight),
                        cornerRadius = CornerRadius(trackW * 0.5f, trackW * 0.5f),
                    )
                }

                // Perilla táctil circular
                drawCircle(
                    color = thumbBorderColor,
                    radius = 11.dp.toPx(),
                    center = Offset(centerX, thumbY),
                )
                drawCircle(
                    color = primary,
                    radius = 8.5.dp.toPx(),
                    center = Offset(centerX, thumbY),
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun yOffsetToGainDb(y: Float, totalHeight: Float): Float {
    val pad = totalHeight * 0.06f
    val usable = (totalHeight - pad * 2f).coerceAtLeast(1f)
    val fraction = 1f - ((y - pad) / usable).coerceIn(0f, 1f)
    val raw = EqualizerState.MIN_GAIN_DB +
        fraction * (EqualizerState.MAX_GAIN_DB - EqualizerState.MIN_GAIN_DB)
    // Redondeo suave a pasos de 0.5 dB para ajuste preciso y cómodo con el dedo.
    return ((raw * 2f).roundToInt() / 2f).coerceIn(
        EqualizerState.MIN_GAIN_DB,
        EqualizerState.MAX_GAIN_DB,
    )
}

private fun formatGainDb(gainDb: Float): String {
    val rounded = (gainDb * 10f).roundToInt() / 10f
    return if (rounded > 0f) {
        String.format(Locale.US, "+%.1f", rounded)
    } else {
        String.format(Locale.US, "%.1f", rounded)
    }
}
