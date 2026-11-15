package com.auramusic.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auramusic.core.model.SleepTimerHelper
import com.auramusic.core.model.SleepTimerMode
import com.auramusic.core.model.SleepTimerState

/**
 * Panel modal del temporizador de apagado automático de Aura Music.
 * Permite elegir duraciones preestablecidas, minutos personalizados (1–480 min)
 * y opciones inteligentes para detener la reproducción al terminar la canción actual
 * o al finalizar toda la música de la cola.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerBottomSheet(
    sleepTimerState: SleepTimerState,
    hasActiveTrack: Boolean,
    queueRemainingCount: Int,
    onDismiss: () -> Unit,
    onStartMinutes: (minutes: Int, fadeOutOnFinish: Boolean) -> Unit,
    onSetEndOfTrack: (fadeOutOnFinish: Boolean) -> Unit,
    onSetEndOfQueue: (fadeOutOnFinish: Boolean) -> Unit,
    onAddMinutes: (deltaMinutes: Int) -> Unit,
    onCancelTimer: () -> Unit,
) {
    var customMinutesInput by remember { mutableStateOf("") }
    var fadeOutOnFinish by remember(sleepTimerState.fadeOutOnFinish) {
        mutableStateOf(sleepTimerState.fadeOutOnFinish)
    }

    val parsedCustomMinutes = SleepTimerHelper.parseCustomMinutesOrNull(customMinutesInput)
    val customInputHasError = customMinutesInput.isNotBlank() && parsedCustomMinutes == null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column {
                        Text(
                            text = "Temporizador",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Pausa automática en segundo plano",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (sleepTimerState.isActive) {
                    TextButton(onClick = onCancelTimer) {
                        Text("Cancelar")
                    }
                }
            }

            // Tarjeta de estado activo cuando el temporizador está corriendo.
            AnimatedVisibility(
                visible = sleepTimerState.isActive,
                enter = fadeIn(tween(180)) + expandVertically(
                    animationSpec = spring(
                        dampingRatio = 0.85f,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(160)),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
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
                                    text = when (sleepTimerState.mode) {
                                        SleepTimerMode.DURATION -> "Temporizador activo"
                                        SleepTimerMode.END_OF_TRACK -> "Parará al terminar esta canción"
                                        SleepTimerMode.END_OF_QUEUE -> "Parará al terminar toda la música"
                                        SleepTimerMode.OFF -> ""
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                if (sleepTimerState.mode == SleepTimerMode.DURATION) {
                                    Text(
                                        text = "Programado: ${SleepTimerHelper.formatPresetLabel(sleepTimerState.configuredMinutes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    )
                                } else {
                                    Text(
                                        text = if (sleepTimerState.mode == SleepTimerMode.END_OF_QUEUE) {
                                            "Pistas restantes en cola: ${queueRemainingCount.coerceAtLeast(1)}"
                                        } else {
                                            "Crossfade pausado para un cierre limpio"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    )
                                }
                            }

                            if (sleepTimerState.mode == SleepTimerMode.DURATION) {
                                Text(
                                    text = SleepTimerHelper.formatCountdown(sleepTimerState.remainingMs),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }

                        if (sleepTimerState.mode == SleepTimerMode.DURATION) {
                            LinearProgressIndicator(
                                progress = { sleepTimerState.progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.16f),
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { onAddMinutes(-5) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("-5 min")
                                }
                                OutlinedButton(
                                    onClick = { onAddMinutes(5) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("+5 min")
                                }
                                FilledTonalButton(
                                    onClick = onCancelTimer,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Detener")
                                }
                            }
                        }
                    }
                }
            }

            // Minutos preestablecidos.
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
                    Text(
                        text = "Duración preestablecida",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    val firstRow = SleepTimerHelper.PRESET_MINUTES.take(4)
                    val secondRow = SleepTimerHelper.PRESET_MINUTES.drop(4)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        firstRow.forEach { minutes ->
                            SleepPresetButton(
                                minutes = minutes,
                                selected = sleepTimerState.mode == SleepTimerMode.DURATION &&
                                    sleepTimerState.configuredMinutes == minutes,
                                onClick = {
                                    onStartMinutes(minutes, fadeOutOnFinish)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        secondRow.forEach { minutes ->
                            SleepPresetButton(
                                minutes = minutes,
                                selected = sleepTimerState.mode == SleepTimerMode.DURATION &&
                                    sleepTimerState.configuredMinutes == minutes,
                                onClick = {
                                    onStartMinutes(minutes, fadeOutOnFinish)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (secondRow.size < 4) {
                            Spacer(modifier = Modifier.weight((4 - secondRow.size).toFloat()))
                        }
                    }
                }
            }

            // Minutos personalizados.
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
                    Text(
                        text = "Tiempo personalizado",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        OutlinedTextField(
                            value = customMinutesInput,
                            onValueChange = { raw ->
                                if (raw.length <= 3 && raw.all { it.isDigit() }) {
                                    customMinutesInput = raw
                                }
                            },
                            label = { Text("Minutos (${SleepTimerHelper.MIN_CUSTOM_MINUTES}-${SleepTimerHelper.MAX_CUSTOM_MINUTES})") },
                            placeholder = { Text("Ej. 25") },
                            singleLine = true,
                            isError = customInputHasError,
                            supportingText = if (customInputHasError) {
                                {
                                    Text(
                                        "Ingresa un número entre " +
                                            "${SleepTimerHelper.MIN_CUSTOM_MINUTES} y " +
                                            "${SleepTimerHelper.MAX_CUSTOM_MINUTES}",
                                    )
                                }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    val valid = parsedCustomMinutes ?: return@KeyboardActions
                                    onStartMinutes(valid, fadeOutOnFinish)
                                    onDismiss()
                                },
                            ),
                            modifier = Modifier.weight(1f),
                        )

                        Button(
                            onClick = {
                                val valid = parsedCustomMinutes ?: return@Button
                                onStartMinutes(valid, fadeOutOnFinish)
                                onDismiss()
                            },
                            enabled = parsedCustomMinutes != null,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Iniciar")
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Opciones basadas en el fin de la música.
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
                    Text(
                        text = "Al terminar la música",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    SleepEndOptionCard(
                        title = "Al terminar esta canción",
                        subtitle = "Pausa automáticamente justo cuando finalice la pista actual",
                        selected = sleepTimerState.mode == SleepTimerMode.END_OF_TRACK,
                        enabled = hasActiveTrack,
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.MusicOff,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onSetEndOfTrack(fadeOutOnFinish)
                            onDismiss()
                        },
                    )

                    SleepEndOptionCard(
                        title = "Al terminar toda la música",
                        subtitle = if (queueRemainingCount > 1) {
                            "Pausa cuando termine la última canción de la cola ($queueRemainingCount restantes)"
                        } else {
                            "Pausa cuando termine toda la cola de reproducción actual"
                        },
                        selected = sleepTimerState.mode == SleepTimerMode.END_OF_QUEUE,
                        enabled = hasActiveTrack,
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            onSetEndOfQueue(fadeOutOnFinish)
                            onDismiss()
                        },
                    )
                }
            }

            // Atenuación suave de volumen al terminar la cuenta atrás.
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Atenuar volumen al finalizar",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Baja suavemente el volumen los últimos 3 segundos antes de pausar",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = fadeOutOnFinish,
                        onCheckedChange = { fadeOutOnFinish = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun SleepPresetButton(
    minutes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = SleepTimerHelper.formatPresetLabel(minutes)
    if (selected) {
        FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SleepEndOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
