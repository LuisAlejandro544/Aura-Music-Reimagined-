package com.auramusic.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auramusic.core.model.AppAccentColor
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.AudioOutputType
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.SystemFolderHelper
import com.auramusic.core.model.SystemFolderStorageState

/**
 * Pantalla principal de Ajustes (menú por categorías).
 * Muestra opciones claras para navegar hacia subpantallas dedicadas en lugar de apilar
 * todas las listas e interruptores en una sola pantalla interminable:
 * 1. Color de la aplicación
 * 2. Colores dinámicos y animación al eliminar
 * 3. Auriculares y salida de audio (AudioDeviceCallback)
 * 4. Botones multimedia (Cable y Bluetooth AVRCP)
 * 5. Carpeta del sistema (Aura Music/Música, Carátulas y .json)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedAccent: AppAccentColor,
    dynamicCoverColorsEnabled: Boolean,
    deleteAnimationsEnabled: Boolean,
    rustEngineInfo: String,
    modifier: Modifier = Modifier,
    onSelectAccent: (AppAccentColor) -> Unit = {},
    onToggleDynamicCoverColors: (Boolean) -> Unit = {},
    onToggleDeleteAnimations: (Boolean) -> Unit = {},
    outputRoute: AudioOutputRouteState = AudioOutputRouteState(),
    headphonePreferences: HeadphonePreferences = HeadphonePreferences(),
    onSetAutoPauseOnDisconnect: (Boolean) -> Unit = {},
    onSetAutoResumeOnConnect: (Boolean) -> Unit = {},
    onSetAutoDisable8dOnSpeaker: (Boolean) -> Unit = {},
    onSetAutoEnableCrossfeedOnHeadphones: (Boolean) -> Unit = {},
    onSetDoubleTapNext: (Boolean) -> Unit = {},
    onSetTripleTapPrevious: (Boolean) -> Unit = {},
    onSetWrapQueueNavigation: (Boolean) -> Unit = {},
    onSetPreviousRestartThresholdMs: (Long) -> Unit = {},
    onSetSeekStepSeconds: (Int) -> Unit = {},
    onOpenAccentTheme: () -> Unit = {},
    onOpenVisualEffects: () -> Unit = {},
    onOpenHeadphoneSettings: () -> Unit = {},
    onOpenMediaButtons: () -> Unit = {},
    systemFolderState: SystemFolderStorageState = SystemFolderStorageState(),
    onOpenSystemFolder: () -> Unit = {},
) {
    val routeIcon = when (outputRoute.outputType) {
        AudioOutputType.BLUETOOTH_HEADSET -> Icons.Rounded.BluetoothAudio
        AudioOutputType.USB_HEADSET -> Icons.Rounded.Usb
        AudioOutputType.WIRED_HEADSET -> Icons.Rounded.Headphones
        AudioOutputType.SPEAKER,
        AudioOutputType.OTHER,
        -> Icons.Rounded.Speaker
    }

    val mediaButtonsSummary = buildList {
        if (headphonePreferences.doubleTapNextEnabled) add("2x Sig.")
        if (headphonePreferences.tripleTapPreviousEnabled) add("3x Ant.")
    }.ifEmpty { listOf("Toque único") }.joinToString(" • ") +
        " • Salto ${headphonePreferences.seekStepSeconds} s"

    val systemFolderSummary = when {
        systemFolderState.isBusy ->
            "Sincronizando (${systemFolderState.processedCount}/${systemFolderState.totalCount})..."
        systemFolderState.isEnabled ->
            "Activa • ${SystemFolderHelper.ROOT_FOLDER_NAME}/" +
                "${SystemFolderHelper.MUSIC_SUBFOLDER_NAME} y ${SystemFolderHelper.COVERS_SUBFOLDER_NAME}"
        else ->
            "Desactivada • Solo almacenamiento interno privado"
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Ajustes",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsCategoryCard(
                    icon = Icons.Rounded.Palette,
                    title = "Color de la aplicación",
                    subtitle = "Tema activo: ${selectedAccent.displayName}",
                    onClick = onOpenAccentTheme,
                    trailingContent = {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(selectedAccent.primaryArgb))
                                .border(
                                    width = 2.dp,
                                    color = Color(selectedAccent.primaryContainerArgb),
                                    shape = CircleShape,
                                ),
                        )
                    },
                )
            }

            item {
                val coverStatus = if (dynamicCoverColorsEnabled) "Activo" else "Desactivado"
                val deleteStatus = if (deleteAnimationsEnabled) "Rust activo" else "Instantáneo"
                SettingsCategoryCard(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Colores dinámicos y animación al eliminar",
                    subtitle = "Carátula: $coverStatus • Eliminar: $deleteStatus",
                    onClick = onOpenVisualEffects,
                )
            }

            item {
                SettingsCategoryCard(
                    icon = routeIcon,
                    title = "Auriculares y salida de audio",
                    subtitle = "Salida activa: ${outputRoute.deviceName}",
                    highlightIcon = outputRoute.isHeadphonesConnected,
                    highlightSubtitle = outputRoute.isHeadphonesConnected,
                    onClick = onOpenHeadphoneSettings,
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Rounded.TouchApp,
                    title = "Botones multimedia (Cable y Bluetooth AVRCP)",
                    subtitle = mediaButtonsSummary,
                    onClick = onOpenMediaButtons,
                )
            }

            item {
                SettingsCategoryCard(
                    icon = Icons.Rounded.Folder,
                    title = "Carpeta del sistema (Aura Music)",
                    subtitle = systemFolderSummary,
                    highlightIcon = systemFolderState.isEnabled,
                    highlightSubtitle = systemFolderState.isEnabled,
                    onClick = onOpenSystemFolder,
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Motor nativo Rust",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = rustEngineInfo,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Incluye lectura de metadatos, carátulas procedurales, ecualizador de " +
                                    "10 bandas, crossfeed binaural, efectos visuales de eliminación y QuickJS-NG.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Subpantalla dedicada para elegir la paleta de color global de Aura Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsAccentThemeScreen(
    selectedAccent: AppAccentColor,
    onSelectAccent: (AppAccentColor) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Color de la aplicación",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Color de la aplicación",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "Tema activo: ${selectedAccent.displayName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            items(AppAccentColor.entries, key = { it.name }) { accent ->
                val isSelected = accent == selectedAccent
                val primaryColor = Color(accent.primaryArgb)
                val containerColor = Color(accent.primaryContainerArgb)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) primaryColor else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .clickable { onSelectAccent(accent) },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(primaryColor)
                                .border(3.dp, containerColor, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Seleccionado",
                                    tint = Color(accent.onPrimaryArgb),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = accent.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = accent.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Subpantalla dedicada para los colores dinámicos de carátula y las animaciones al eliminar (Rust).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsVisualEffectsScreen(
    dynamicCoverColorsEnabled: Boolean,
    deleteAnimationsEnabled: Boolean,
    onToggleDynamicCoverColors: (Boolean) -> Unit,
    onToggleDeleteAnimations: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Colores dinámicos y eliminación",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SettingsToggleCard(
                    icon = Icons.Rounded.AutoAwesome,
                    title = "Colores dinámicos de carátula",
                    subtitle = if (dynamicCoverColorsEnabled) {
                        "Activo • Adapta el mini reproductor y reproductor a la portada"
                    } else {
                        "Desactivado • Usa el color global de la app"
                    },
                    checked = dynamicCoverColorsEnabled,
                    onCheckedChange = onToggleDynamicCoverColors,
                )
            }

            item {
                SettingsToggleCard(
                    icon = Icons.Rounded.AutoFixHigh,
                    title = "Animaciones al eliminar (Rust)",
                    subtitle = if (deleteAnimationsEnabled) {
                        "Activo • Cenizas, papel quemándose o aura extraña al azar"
                    } else {
                        "Desactivado • Eliminación instantánea tras confirmar"
                    },
                    checked = deleteAnimationsEnabled,
                    onCheckedChange = onToggleDeleteAnimations,
                )
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Cómo funcionan estos efectos",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Los colores dinámicos extraen la paleta dominante de la portada mediante " +
                                "Palette + MaterialKolor con transiciones suaves. Las animaciones de eliminación " +
                                "se procesan por píxel en Rust sobre DirectByteBuffer eligiendo al azar entre " +
                                "cenizas, papel quemándose o aura extraña.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Subpantalla dedicada para la detección inteligente de salida de audio y automatizaciones con auriculares.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHeadphonesScreen(
    outputRoute: AudioOutputRouteState,
    headphonePreferences: HeadphonePreferences,
    onSetAutoPauseOnDisconnect: (Boolean) -> Unit,
    onSetAutoResumeOnConnect: (Boolean) -> Unit,
    onSetAutoDisable8dOnSpeaker: (Boolean) -> Unit,
    onSetAutoEnableCrossfeedOnHeadphones: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Auriculares y salida de audio",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HeadphoneOutputSettingsCard(
                    outputRoute = outputRoute,
                    prefs = headphonePreferences,
                    onSetAutoPauseOnDisconnect = onSetAutoPauseOnDisconnect,
                    onSetAutoResumeOnConnect = onSetAutoResumeOnConnect,
                    onSetAutoDisable8dOnSpeaker = onSetAutoDisable8dOnSpeaker,
                    onSetAutoEnableCrossfeedOnHeadphones = onSetAutoEnableCrossfeedOnHeadphones,
                )
            }
        }
    }
}

/**
 * Subpantalla dedicada para configurar botones multimedia de cable (KEYCODE_HEADSETHOOK) y Bluetooth AVRCP.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMediaButtonsScreen(
    headphonePreferences: HeadphonePreferences,
    onSetDoubleTapNext: (Boolean) -> Unit,
    onSetTripleTapPrevious: (Boolean) -> Unit,
    onSetWrapQueueNavigation: (Boolean) -> Unit,
    onSetPreviousRestartThresholdMs: (Long) -> Unit,
    onSetSeekStepSeconds: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Botones multimedia",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Cable y Bluetooth AVRCP",
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                MediaButtonsTuningCard(
                    prefs = headphonePreferences,
                    onSetDoubleTapNext = onSetDoubleTapNext,
                    onSetTripleTapPrevious = onSetTripleTapPrevious,
                    onSetWrapQueueNavigation = onSetWrapQueueNavigation,
                    onSetPreviousRestartThresholdMs = onSetPreviousRestartThresholdMs,
                    onSetSeekStepSeconds = onSetSeekStepSeconds,
                )
            }
        }
    }
}

/**
 * Subpantalla especial dedicada para activar, migrar y sincronizar la carpeta pública
 * del sistema `Aura Music` (`Música`, `Carátulas` y archivos `.json`).
 * Por defecto permanece desactivada para mantener la memoria privada intacta salvo que
 * el usuario decida activarla.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSystemFolderScreen(
    state: SystemFolderStorageState,
    onToggleEnabled: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onSyncNow: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Carpeta del sistema",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = SystemFolderHelper.ROOT_FOLDER_NAME,
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.isEnabled) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = if (state.isEnabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.CreateNewFolder,
                                    contentDescription = null,
                                    tint = if (state.isEnabled) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.folderDisplayPath,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.isEnabled) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (state.isEnabled) {
                                    "Activa • Las canciones y carátulas se sincronizan automáticamente"
                                } else {
                                    "Desactivada por defecto • Usa la memoria privada de la app"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.isEnabled) {
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
                SettingsToggleCard(
                    icon = Icons.Rounded.Folder,
                    title = "Crear y usar carpeta Aura Music",
                    subtitle = if (state.isEnabled) {
                        "Activada • Traspasa canciones, carátulas y archivos .json al sistema"
                    } else {
                        "Al activar, traspasa tus canciones, carátulas y .json a esta carpeta"
                    },
                    checked = state.isEnabled,
                    enabled = !state.isBusy,
                    onCheckedChange = onToggleEnabled,
                )
            }

            if (state.isBusy) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = state.statusMessage ?: "Sincronizando carpeta Aura Music...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.totalCount > 0) {
                                LinearProgressIndicator(
                                    progress = { state.progressFraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = "${state.processedCount} de ${state.totalCount} canciones procesadas",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            } else if (!state.statusMessage.isNullOrBlank()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = state.statusMessage.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Contenido de la carpeta ${SystemFolderHelper.ROOT_FOLDER_NAME}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        FolderStructureRow(
                            icon = Icons.Rounded.LibraryMusic,
                            title = "${SystemFolderHelper.ROOT_FOLDER_NAME}/${SystemFolderHelper.MUSIC_SUBFOLDER_NAME}",
                            description = "Almacena tus canciones traspasadas, descargas y pistas nuevas. " +
                                "Si copias una canción aquí y abres Aura Music, aparecerá automáticamente.",
                        )
                        FolderStructureRow(
                            icon = Icons.Rounded.Image,
                            title = "${SystemFolderHelper.ROOT_FOLDER_NAME}/${SystemFolderHelper.COVERS_SUBFOLDER_NAME}",
                            description = "Guarda las portadas embebidas, de galería (WebP) y procedurales " +
                                "con archivo .nomedia para no llenar tu galería.",
                        )
                        FolderStructureRow(
                            icon = Icons.Rounded.Description,
                            title = "${SystemFolderHelper.ROOT_FOLDER_NAME}/*.json",
                            description = "Respaldo legible de tu biblioteca (${SystemFolderHelper.LIBRARY_JSON_FILE_NAME}) " +
                                "y configuración de audio (${SystemFolderHelper.PLAYER_CONFIG_JSON_FILE_NAME}).",
                        )
                    }
                }
            }

            if (!state.hasStoragePermission) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Permiso necesario para carpeta pública",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Android requiere autorizar el acceso a archivos del sistema para que " +
                                    "Aura Music cree la carpeta raíz /Aura Music y detecte canciones " +
                                    "copiadas desde tu explorador.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onRequestPermission,
                                enabled = !state.isBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Conceder permiso y activar")
                            }
                        }
                    }
                }
            } else if (state.isEnabled) {
                item {
                    FilledTonalButton(
                        onClick = onSyncNow,
                        enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Escanear y sincronizar carpeta ahora")
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Si desactivas esta opción más adelante, Aura Music conservará intactos " +
                                "todos tus archivos en la carpeta /Aura Music sin borrar nada.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderStructureRow(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlightIcon: Boolean = false,
    highlightSubtitle: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = if (highlightIcon) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (highlightIcon) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlightSubtitle) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(8.dp))
                trailingContent()
            }
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
private fun HeadphoneOutputSettingsCard(
    outputRoute: AudioOutputRouteState,
    prefs: HeadphonePreferences,
    onSetAutoPauseOnDisconnect: (Boolean) -> Unit,
    onSetAutoResumeOnConnect: (Boolean) -> Unit,
    onSetAutoDisable8dOnSpeaker: (Boolean) -> Unit,
    onSetAutoEnableCrossfeedOnHeadphones: (Boolean) -> Unit,
) {
    val routeIcon = when (outputRoute.outputType) {
        AudioOutputType.BLUETOOTH_HEADSET -> Icons.Rounded.BluetoothAudio
        AudioOutputType.USB_HEADSET -> Icons.Rounded.Usb
        AudioOutputType.WIRED_HEADSET -> Icons.Rounded.Headphones
        AudioOutputType.SPEAKER,
        AudioOutputType.OTHER,
        -> Icons.Rounded.Speaker
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (outputRoute.isHeadphonesConnected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = routeIcon,
                            contentDescription = null,
                            tint = if (outputRoute.isHeadphonesConnected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auriculares y salida de audio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Salida activa: ${outputRoute.deviceName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (outputRoute.isHeadphonesConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            CompactSettingSwitchRow(
                title = "Pausar al desconectar",
                subtitle = "Detiene la música automáticamente si desconectas tus auriculares.",
                checked = prefs.autoPauseOnDisconnect,
                onCheckedChange = onSetAutoPauseOnDisconnect,
            )

            CompactSettingSwitchRow(
                title = "Reanudar al conectar auriculares",
                subtitle = "Retoma la reproducción cargada cuando conectas cable, USB-C o Bluetooth.",
                checked = prefs.autoResumeOnConnect,
                onCheckedChange = onSetAutoResumeOnConnect,
            )

            CompactSettingSwitchRow(
                title = "Pausar espacializador 8D en altavoz",
                subtitle = "Evita el efecto de rotación sobre el altavoz y lo restaura al usar audífonos.",
                checked = prefs.autoDisable8dOnSpeaker,
                onCheckedChange = onSetAutoDisable8dOnSpeaker,
            )

            CompactSettingSwitchRow(
                title = "Crossfeed binaural automático",
                subtitle = "Activa automáticamente el Crossfeed estático en Rust al conectar auriculares.",
                checked = prefs.autoEnableCrossfeedOnHeadphones,
                onCheckedChange = onSetAutoEnableCrossfeedOnHeadphones,
            )
        }
    }
}

@Composable
private fun MediaButtonsTuningCard(
    prefs: HeadphonePreferences,
    onSetDoubleTapNext: (Boolean) -> Unit,
    onSetTripleTapPrevious: (Boolean) -> Unit,
    onSetWrapQueueNavigation: (Boolean) -> Unit,
    onSetPreviousRestartThresholdMs: (Long) -> Unit,
    onSetSeekStepSeconds: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.TouchApp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Botones multimedia (Cable y Bluetooth AVRCP)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Personaliza gestos de botón único y saltos AVRCP",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            CompactSettingSwitchRow(
                title = "Doble toque = Siguiente pista",
                subtitle = "Para auriculares con un único botón físico (KEYCODE_HEADSETHOOK).",
                checked = prefs.doubleTapNextEnabled,
                onCheckedChange = onSetDoubleTapNext,
            )

            CompactSettingSwitchRow(
                title = "Triple toque = Pista anterior",
                subtitle = "Pulsa 3 veces seguidas el botón del cable para volver atrás.",
                checked = prefs.tripleTapPreviousEnabled,
                onCheckedChange = onSetTripleTapPrevious,
            )

            CompactSettingSwitchRow(
                title = "Navegación circular en la cola",
                subtitle = "Al pulsar Siguiente en la última pista o Anterior en la primera, da la vuelta.",
                checked = prefs.wrapQueueNavigation,
                onCheckedChange = onSetWrapQueueNavigation,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Umbral para reiniciar al pulsar Anterior",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Si la canción superó este tiempo, Anterior la reinicia desde 0:00 en lugar de saltar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeadphonePreferences.PREVIOUS_THRESHOLD_OPTIONS_MS.forEach { optionMs ->
                        val selected = prefs.previousRestartThresholdMs == optionMs
                        val label = HeadphoneRoutingHelper.formatPreviousThresholdLabel(optionMs)
                        if (selected) {
                            FilledTonalButton(
                                onClick = { onSetPreviousRestartThresholdMs(optionMs) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSetPreviousRestartThresholdMs(optionMs) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Salto rápido AVRCP (Avance / Retroceso)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HeadphonePreferences.SEEK_STEP_OPTIONS_SECONDS.forEach { seconds ->
                        val selected = prefs.seekStepSeconds == seconds
                        val label = "$seconds s"
                        if (selected) {
                            FilledTonalButton(
                                onClick = { onSetSeekStepSeconds(seconds) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSetSeekStepSeconds(seconds) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
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
private fun CompactSettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
