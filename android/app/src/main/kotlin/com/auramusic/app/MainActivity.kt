package com.auramusic.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Actividad principal de Aura Music.
 * Aplica el tema dinámico elegido por el usuario, procesa archivos externos abiertos mediante
 * "Abrir con" (`ACTION_VIEW`), muestra una pantalla de carga inicial mientras se hidratan los
 * datos locales y monta la navegación modular de Jetpack Compose.
 */
class MainActivity : ComponentActivity() {
    private val viewModel: AuraViewModel by viewModels {
        AuraViewModel.Factory((application as AuraApplication).container)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* La reproducción local sigue funcionando aunque el usuario rechace notificaciones */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
            val isAppReady by viewModel.isAppReady.collectAsStateWithLifecycle()
            AuraMusicTheme(accentColor = accentColor) {
                Crossfade(
                    targetState = isAppReady,
                    animationSpec = tween(durationMillis = 260),
                    label = "AuraStartupCrossfade",
                ) { ready ->
                    if (ready) {
                        AuraAppRoot(viewModel = viewModel)
                    } else {
                        AuraStartupLoadingScreen()
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
