package com.auramusic.feature.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.auramusic.core.model.DeleteAnimationEffect
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.OnboardingHelper
import com.auramusic.core.model.OnboardingPage
import com.auramusic.core.model.OnboardingPermissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Flujo de bienvenida y permisos mostrado únicamente la primera vez que el usuario instala Aura Music.
 *
 * Consta de dos pantallas dedicadas:
 * 1. Guía clara de funcionamiento (biblioteca privada SAF, motor DSP Rust, descargas multipágina
 *    con `yt-dlp` para YouTube/TikTok/Instagram/etc. y procesos aislados para ahorrar RAM).
 * 2. Configuración transparente de los permisos reales que solicita la aplicación.
 *
 * Al pasar de la página 1 a la página 2, captura la pantalla completa y ejecuta el efecto
 * nativo de **papel quemándose** (`DeleteAnimationEffect.BURNING_PAPER`) en Rust para revelar
 * la página de permisos debajo de las llamas y brasas.
 */
@Composable
fun AuraOnboardingScreen(
    permissionStatus: OnboardingPermissionStatus,
    accentArgb: Int,
    deleteEffectRenderer: DeleteEffectFrameRenderer?,
    onRequestNotificationsPermission: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onCompleteOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var currentPage by rememberSaveable { mutableStateOf(OnboardingPage.WELCOME_GUIDE) }
    var isBurningTransition by remember { mutableStateOf(false) }
    var renderedBurnFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    val captureLayer = rememberGraphicsLayer()
    val coroutineScope = rememberCoroutineScope()

    fun startBurningTransitionToPermissions() {
        if (isBurningTransition || currentPage == OnboardingPage.PERMISSIONS) return
        if (deleteEffectRenderer == null) {
            currentPage = OnboardingPage.PERMISSIONS
            return
        }

        isBurningTransition = true
        coroutineScope.launch {
            var frameBitmapA: Bitmap? = null
            var frameBitmapB: Bitmap? = null
            try {
                withFrameNanos { }
                val capturedBitmap = runCatching {
                    captureLayer.toImageBitmap().asAndroidBitmap()
                }.getOrNull()

                if (capturedBitmap == null || capturedBitmap.width <= 0 || capturedBitmap.height <= 0) {
                    return@launch
                }

                // Escala la captura fuera del hilo principal para sostener 60 fps fluidos en 32 y 64 bits.
                val prepared = withContext(Dispatchers.Default) {
                    val softwareBitmap = if (capturedBitmap.config != Bitmap.Config.ARGB_8888) {
                        capturedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        capturedBitmap
                    } ?: return@withContext null

                    val targetWidth = softwareBitmap.width.coerceAtMost(320).coerceAtLeast(1)
                    val targetHeight = (
                        (softwareBitmap.height.toFloat() / softwareBitmap.width.toFloat()) * targetWidth
                        ).roundToInt().coerceAtLeast(1)
                    val scaledBitmap = if (
                        targetWidth != softwareBitmap.width ||
                        targetHeight != softwareBitmap.height
                    ) {
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
                } ?: return@launch

                val (width, height, srcBuffer) = prepared
                val dstBuffer = ByteBuffer.allocateDirect(width * height * 4)
                frameBitmapA = createBitmap(width, height)
                frameBitmapB = createBitmap(width, height)
                var useBufferA = true

                val startNanos = System.nanoTime()
                val durationMs = 1_150L
                val seed = (startNanos xor 0x41555241L).toInt()

                while (true) {
                    val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
                    val progress = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

                    srcBuffer.rewind()
                    dstBuffer.rewind()
                    val ok = deleteEffectRenderer.renderFrame(
                        sourceDirectRgba = srcBuffer,
                        outputDirectRgba = dstBuffer,
                        width = width,
                        height = height,
                        effect = DeleteAnimationEffect.BURNING_PAPER,
                        progress = progress,
                        seed = seed,
                        accentArgb = accentArgb,
                    )
                    if (ok) {
                        dstBuffer.rewind()
                        val targetBmp = if (useBufferA) frameBitmapA else frameBitmapB
                        targetBmp?.let { bmp ->
                            bmp.copyPixelsFromBuffer(dstBuffer)
                            renderedBurnFrame = bmp.asImageBitmap()
                        }
                        useBufferA = !useBufferA
                    }
                    if (progress >= 1f) break
                    withFrameNanos { }
                }
            } finally {
                renderedBurnFrame = null
                currentPage = OnboardingPage.PERMISSIONS
                isBurningTransition = false
                frameBitmapA?.recycle()
                frameBitmapB?.recycle()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Capa inferior: Página de permisos ya montada debajo para revelarse conforme arde la hoja 1.
        if (currentPage == OnboardingPage.PERMISSIONS || isBurningTransition) {
            PermissionsSetupPage(
                permissionStatus = permissionStatus,
                onBackToGuide = {
                    if (!isBurningTransition) {
                        currentPage = OnboardingPage.WELCOME_GUIDE
                    }
                },
                onRequestNotificationsPermission = onRequestNotificationsPermission,
                onRequestStoragePermission = onRequestStoragePermission,
                onCompleteOnboarding = onCompleteOnboarding,
            )
        }

        // Capa superior: Página 1 (Guía de funcionamiento); solo graba en captureLayer al quemarse en Rust.
        if (currentPage == OnboardingPage.WELCOME_GUIDE || isBurningTransition) {
            val pageModifier = if (isBurningTransition) {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (renderedBurnFrame == null) 1f else 0f
                    }
                    .drawWithContent {
                        if (renderedBurnFrame == null) {
                            captureLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(captureLayer)
                        }
                    }
            } else {
                Modifier.fillMaxSize()
            }
            Box(modifier = pageModifier) {
                WelcomeGuidePage(
                    isTransitioning = isBurningTransition,
                    onNextToPermissions = { startBurningTransitionToPermissions() },
                )
            }
        }

        val activeBurnFrame = renderedBurnFrame
        if (activeBurnFrame != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = activeBurnFrame,
                    dstSize = IntSize(
                        width = size.width.roundToInt().coerceAtLeast(1),
                        height = size.height.roundToInt().coerceAtLeast(1),
                    ),
                )
            }
        }
    }
}

@Composable
private fun WelcomeGuidePage(
    isTransitioning: Boolean,
    onNextToPermissions: () -> Unit,
) {
    val bgBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f),
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface,
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(bgBrush)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = "Paso 1 de 2 • Cómo funciona",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Bienvenido a Aura Music",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Reproductor local de alta fidelidad con motor nativo en Rust y descargador multipágina en Python.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OnboardingFeatureCard(
                icon = Icons.Rounded.LibraryMusic,
                title = "1. Tu biblioteca privada bajo tu control",
                description = "Importa canciones sueltas o carpetas completas con el selector del sistema sin escaneos invasivos. " +
                    "Edita títulos, artistas, carátulas en WebP sin pérdida y activa si quieres la carpeta visible Aura Music en Ajustes.",
            )

            OnboardingFeatureCard(
                icon = Icons.Rounded.GraphicEq,
                title = "2. Procesamiento de audio DSP en Rust",
                description = "Ecualizador de 10 bandas, Reverb estéreo, modo Slowed con calidez analógica, Audio 8D envolvente, " +
                    "Crossfeed para auriculares, Crossfade sin cortes y control de tono de voz.",
            )

            OnboardingFeatureCard(
                icon = Icons.Rounded.CloudDownload,
                title = "3. Descargas de audio desde múltiples páginas",
                description = "Gracias a yt-dlp y QuickJS-NG puedes analizar y bajar audio de ${OnboardingHelper.buildSupportedPlatformsSummary()} " +
                    "Todo corre en segundo plano con progreso en tiempo real.",
            )

            OnboardingFeatureCard(
                icon = Icons.Rounded.Speed,
                title = "4. Diseñada para ahorrar RAM al jugar",
                description = "El reproductor y el descargador corren en procesos aislados que liberan memoria al entrar en segundo plano " +
                    "y mantienen controles multimedia en auriculares y notificación.",
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onNextToPermissions,
                    enabled = !isTransitioning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = "Siguiente: Permisos de la app",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsSetupPage(
    permissionStatus: OnboardingPermissionStatus,
    onBackToGuide: () -> Unit,
    onRequestNotificationsPermission: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onCompleteOnboarding: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = "Paso 2 de 2 • Permisos y transparencia",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Permisos que usa Aura Music",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Esta pantalla solo aparece en tu primer inicio. Concede los permisos que necesites ahora o cámbialos después en Ajustes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            PermissionActionCard(
                icon = Icons.Rounded.NotificationsActive,
                title = "Notificaciones (reproductor y descargas)",
                description = "Permite mostrar el reproductor multimedia en la barra de notificaciones, los controles de bloqueo " +
                    "y el progreso en vivo de las descargas en segundo plano con yt-dlp.",
                statusLabel = when {
                    !permissionStatus.notificationsRequiredBySystem -> "Incluido en tu versión de Android"
                    permissionStatus.notificationsGranted -> "Permiso concedido"
                    else -> "Pendiente de conceder"
                },
                isGranted = permissionStatus.isNotificationReady,
                actionLabel = if (permissionStatus.isNotificationReady) "Concedido" else "Permitir notificaciones",
                onAction = onRequestNotificationsPermission,
            )

            PermissionActionCard(
                icon = Icons.Rounded.FolderOpen,
                title = "Almacenamiento (carpeta pública opcional)",
                description = "Opcional. Solo se necesita si quieres usar la carpeta visible \"Aura Music\" en tu almacenamiento interno " +
                    "para copiar canciones, carátulas y respaldos .json. La importación normal con el selector funciona sin este permiso.",
                statusLabel = if (permissionStatus.storageGranted) {
                    "Permiso concedido"
                } else {
                    "Opcional • Sin activar"
                },
                isGranted = permissionStatus.storageGranted,
                actionLabel = if (permissionStatus.storageGranted) "Concedido" else "Conceder acceso",
                onAction = onRequestStoragePermission,
            )

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
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Permisos automáticos del sistema",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        text = "• Servicios en segundo plano y suspensión (FOREGROUND_SERVICE / WAKE_LOCK): evitan cortes al reproducir con pantalla apagada o bajar audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "• Internet y estado de red (INTERNET / ACCESS_NETWORK_STATE): usados exclusivamente cuando descargas enlaces o actualizas yt-dlp y yt-dlp-ejs desde PyPI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Sin telemetría, anuncios ni rastreo en segundo plano.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onBackToGuide,
                    modifier = Modifier
                        .weight(0.9f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Volver")
                }

                Button(
                    onClick = onCompleteOnboarding,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        text = "Empezar a escuchar",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingFeatureCard(
    icon: ImageVector,
    title: String,
    description: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PermissionActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    statusLabel: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (isGranted) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            null
        },
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
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (isGranted) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isGranted) Icons.Rounded.CheckCircle else icon,
                            contentDescription = null,
                            tint = if (isGranted) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isGranted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onAction,
                enabled = !isGranted,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = actionLabel,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
