package com.auramusic.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.auramusic.core.importer.AuraSystemStorageManager
import com.auramusic.core.model.DeleteEffectFrameRenderer
import com.auramusic.core.model.OnboardingPermissionStatus
import com.auramusic.core.rust.AuraRustBridge
import com.auramusic.feature.onboarding.AuraOnboardingScreen

/**
 * Actividad principal de Aura Music.
 * Aplica el tema dinámico elegido por el usuario, procesa archivos externos abiertos mediante
 * "Abrir con" (`ACTION_VIEW`), muestra una pantalla de carga inicial mientras se hidratan los
 * datos locales, presenta el onboarding con papel quemándose solo la primera vez que se instala
 * la app y monta la navegación modular de Jetpack Compose.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: AuraViewModel by viewModels {
        AuraViewModel.Factory((application as AuraApplication).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
            val isAppReady by viewModel.isAppReady.collectAsStateWithLifecycle()
            val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()

            var notificationsGranted by remember {
                mutableStateOf(hasNotificationPermission())
            }
            var storageGranted by remember {
                mutableStateOf(AuraSystemStorageManager.hasStoragePermission(this@MainActivity))
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted ->
                notificationsGranted = granted || hasNotificationPermission()
            }

            val storagePermissionLauncher = rememberAuraSystemStoragePermissionLauncher(
                context = this@MainActivity,
                onPermissionEvaluated = { granted ->
                    storageGranted = granted || AuraSystemStorageManager.hasStoragePermission(this@MainActivity)
                },
            )

            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        notificationsGranted = hasNotificationPermission()
                        storageGranted = AuraSystemStorageManager.hasStoragePermission(this@MainActivity)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }

            val onboardingDeleteRenderer = remember {
                DeleteEffectFrameRenderer { src, dst, width, height, effect, progress, seed, accentArgb ->
                    AuraRustBridge.renderDeleteEffectFrame(
                        sourceBuffer = src,
                        outputBuffer = dst,
                        width = width,
                        height = height,
                        effectType = effect.id,
                        progress = progress,
                        seed = seed,
                        accentArgb = accentArgb,
                    )
                }
            }

            AuraMusicTheme(accentColor = accentColor) {
                Crossfade(
                    targetState = isAppReady,
                    animationSpec = tween(durationMillis = 260),
                    label = "AuraStartupCrossfade",
                ) { ready ->
                    if (!ready) {
                        AuraStartupLoadingScreen()
                    } else {
                        Crossfade(
                            targetState = onboardingCompleted,
                            animationSpec = tween(durationMillis = 280),
                            label = "AuraOnboardingCrossfade",
                        ) { completed ->
                            if (!completed) {
                                AuraOnboardingScreen(
                                    permissionStatus = OnboardingPermissionStatus(
                                        notificationsGranted = notificationsGranted,
                                        notificationsRequiredBySystem = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                                        storageGranted = storageGranted,
                                    ),
                                    accentArgb = accentColor.primaryArgb.toInt(),
                                    deleteEffectRenderer = onboardingDeleteRenderer,
                                    onRequestNotificationsPermission = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    onRequestStoragePermission = storagePermissionLauncher,
                                    onCompleteOnboarding = viewModel::completeOnboarding,
                                )
                            } else {
                                AuraAppRoot(viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onUiForeground()
    }

    override fun onStop() {
        viewModel.onUiBackground()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        if (incoming.getBooleanExtra(AuraApplication.EXTRA_OPEN_PLAYER, false)) {
            viewModel.requestOpenPlayer()
        }
        if (incoming.action == Intent.ACTION_VIEW) {
            val targetUri: Uri = incoming.data
                ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                ?: return
            viewModel.openExternalAudioUri(targetUri)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
