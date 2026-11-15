package com.auramusic.app

import android.app.Application
import android.content.Context
import android.content.Intent
import com.auramusic.core.database.AudioEffectsPreferencesRepository
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.database.EqualizerPreferencesRepository
import com.auramusic.core.database.HeadphonePreferencesRepository
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.PlaybackPreferencesRepository
import com.auramusic.core.database.ThemePreferencesRepository
import com.auramusic.core.downloader.AuraDownloadHost
import com.auramusic.core.downloader.YtDlpAudioDownloader
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.player.AuraAudioOutputMonitor
import com.auramusic.core.player.AuraPlaybackTuningProvider
import com.auramusic.core.player.AuraPlayerController
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Contenedor modular de dependencias de Aura Music.
 * Centraliza el ciclo de vida de la base de datos Room, DataStore, el importador manual,
 * el monitor de salida de audio (`AudioDeviceCallback`), el motor de descargas
 * (Chaquopy + yt-dlp + QuickJS-NG), el controlador Media3 ExoPlayer y el motor nativo en Rust.
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

    val playbackPreferencesRepository: PlaybackPreferencesRepository by lazy {
        PlaybackPreferencesRepository(appContext)
    }

    val audioEffectsPreferencesRepository: AudioEffectsPreferencesRepository by lazy {
        AudioEffectsPreferencesRepository(appContext)
    }

    val headphonePreferencesRepository: HeadphonePreferencesRepository by lazy {
        HeadphonePreferencesRepository(appContext)
    }

    val audioOutputMonitor: AuraAudioOutputMonitor by lazy {
        AuraAudioOutputMonitor(appContext)
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
 * Implementa [AuraDownloadHost] para que [com.auramusic.core.downloader.AuraDownloadForegroundService]
 * pueda ejecutar descargas en segundo plano aunque el usuario salga de la app, y
 * [AuraPlaybackTuningProvider] para suministrar en tiempo real ajustes, ruta de salida y efectos.
 */
class AuraApplication : Application(), AuraDownloadHost, AuraPlaybackTuningProvider {
    lateinit var container: AuraAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AuraAppContainer(this)
        container.audioOutputMonitor.start()
        appScope.launch {
            runCatching {
                val eq = container.equalizerPreferencesRepository.equalizerState.first()
                AuraRustBridge.setEqualizerConfig(eq.isEnabled, eq.bandGainsDb)
                val fx = container.audioEffectsPreferencesRepository.audioEffectsState.first()
                val hp = container.headphonePreferencesRepository.headphonePreferences.first()
                val route = container.audioOutputMonitor.outputRoute.value
                val effective = HeadphoneRoutingHelper.resolveEffectiveRustEffects(
                    effects = fx,
                    outputRoute = route,
                    prefs = hp,
                )
                AuraRustBridge.setAudioEffectsConfig(
                    reverbEnabled = fx.reverbEnabled,
                    reverbRoomSize = fx.reverbRoomSize,
                    reverbDamping = fx.reverbDamping,
                    reverbWetMix = fx.reverbWetMix,
                    reverbWidth = fx.reverbWidth,
                    slowedWarmthEnabled = fx.isSlowedWarmthActive,
                    slowedWarmthAmount = fx.slowedWarmth,
                    spatial8dEnabled = effective.spatial8dEnabled,
                    spatial8dSpeed = fx.spatial8dSpeed,
                    spatial8dDepth = fx.spatial8dDepth,
                    crossfeedEnabled = effective.crossfeedEnabled,
                    crossfeedStrength = fx.crossfeedStrength,
                    crossfeedAngle = fx.crossfeedAngle,
                )
            }
        }
    }

    override fun provideAudioDownloader(): YtDlpAudioDownloader = container.ytDlpAudioDownloader

    override fun createDownloaderNotificationIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    override fun observePlaybackTuning(): Flow<PlaybackTuning> =
        container.playbackPreferencesRepository.playbackTuning

    override fun observeAudioEffects(): Flow<AudioEffectsState> =
        container.audioEffectsPreferencesRepository.audioEffectsState

    override fun observeHeadphonePreferences(): Flow<HeadphonePreferences> =
        container.headphonePreferencesRepository.headphonePreferences

    override fun observeAudioOutputRoute(): Flow<AudioOutputRouteState> =
        container.audioOutputMonitor.outputRoute

    override fun createOpenPlayerIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }

    companion object {
        const val EXTRA_OPEN_PLAYER = "aura_open_player"
    }
}
