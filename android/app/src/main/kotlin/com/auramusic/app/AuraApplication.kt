package com.auramusic.app

import android.app.Application
import android.content.Context
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.database.EqualizerPreferencesRepository
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.ThemePreferencesRepository
import com.auramusic.core.downloader.YtDlpAudioDownloader
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.player.AuraPlayerController
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Contenedor modular de dependencias de Aura Music.
 * Centraliza el ciclo de vida de la base de datos Room, DataStore, el importador manual,
 * el motor de descargas (Chaquopy + yt-dlp + QuickJS-NG), el controlador Media3 ExoPlayer
 * y la información del motor nativo en Rust.
 */
class AuraAppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database by lazy {
        MusicRepository.buildDatabase(appContext)
    }

    val musicRepository: MusicRepository by lazy {
        MusicRepository(database)
    }

    val themePreferencesRepository: ThemePreferencesRepository by lazy {
        ThemePreferencesRepository(appContext)
    }

    val equalizerPreferencesRepository: EqualizerPreferencesRepository by lazy {
        EqualizerPreferencesRepository(appContext)
    }

    val downloaderPreferencesRepository: DownloaderPreferencesRepository by lazy {
        DownloaderPreferencesRepository(appContext)
    }

    val audioImporter: AudioImporter by lazy {
        AudioImporter(
            context = appContext,
            repository = musicRepository,
        )
    }

    val ytDlpAudioDownloader: YtDlpAudioDownloader by lazy {
        YtDlpAudioDownloader(
            context = appContext,
            audioImporter = audioImporter,
            preferencesRepository = downloaderPreferencesRepository,
        )
    }

    val playerController: AuraPlayerController by lazy {
        AuraPlayerController(appContext)
    }

    val rustEngineInfo: String by lazy {
        AuraRustBridge.getEngineInfo()
    }
}

/**
 * Clase Application de Aura Music.
 */
class AuraApplication : Application() {
    lateinit var container: AuraAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AuraAppContainer(this)
        appScope.launch {
            runCatching {
                val eq = container.equalizerPreferencesRepository.equalizerState.first()
                AuraRustBridge.setEqualizerConfig(eq.isEnabled, eq.bandGainsDb)
            }
        }
    }
}
