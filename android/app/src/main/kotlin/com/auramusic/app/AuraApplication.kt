package com.auramusic.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.auramusic.core.database.AudioEffectsPreferencesRepository
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.database.EqualizerPreferencesRepository
import com.auramusic.core.database.HeadphonePreferencesRepository
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.PlaybackPreferencesRepository
import com.auramusic.core.database.StoragePreferencesRepository
import com.auramusic.core.database.ThemePreferencesRepository
import com.auramusic.core.downloader.AuraDownloadForegroundService
import com.auramusic.core.downloader.AuraDownloadHost
import com.auramusic.core.downloader.AuraDownloaderRemoteClient
import com.auramusic.core.downloader.YtDlpAudioDownloader
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.importer.AuraSystemStorageManager
import com.auramusic.core.model.AudioEffectsState
import com.auramusic.core.model.AudioOutputRouteState
import com.auramusic.core.model.AuraProcessRole
import com.auramusic.core.model.AuraProcessRoleResolver
import com.auramusic.core.model.EqualizerState
import com.auramusic.core.model.HeadphonePreferences
import com.auramusic.core.model.HeadphoneRoutingHelper
import com.auramusic.core.model.MemoryTrimAction
import com.auramusic.core.model.MemoryTrimPolicy
import com.auramusic.core.model.PlaybackTuning
import com.auramusic.core.player.AuraAudioOutputMonitor
import com.auramusic.core.player.AuraPlaybackTuningProvider
import com.auramusic.core.player.AuraPlayerConfigSnapshot
import com.auramusic.core.player.AuraPlayerConfigStore
import com.auramusic.core.player.AuraPlayerController
import com.auramusic.core.rust.AuraRustBridge
import com.auramusic.feature.player.clearCoverColorCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Contenedor modular de dependencias del proceso principal (`MAIN`) de Aura Music.
 * Centraliza Room, DataStore, el importador manual, el cliente IPC hacia el proceso
 * aislado `:downloader`, el monitor de salida y el controlador Media3 hacia `:player`.
 *
 * Nunca inicializa Python/Chaquopy ni ExoPlayer local en este proceso.
 */
class AuraAppContainer(
    context: Context,
) {
    val appContext: Context = context.applicationContext

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

    val storagePreferencesRepository: StoragePreferencesRepository by lazy {
        StoragePreferencesRepository(appContext)
    }

    val audioOutputMonitor: AuraAudioOutputMonitor by lazy {
        AuraAudioOutputMonitor(appContext)
    }

    val audioImporter: AudioImporter by lazy {
        AudioImporter(
            context = appContext,
            repository = musicRepository,
            storagePreferencesRepository = storagePreferencesRepository,
        )
    }

    val systemStorageManager: AuraSystemStorageManager by lazy {
        AuraSystemStorageManager(
            context = appContext,
            repository = musicRepository,
            storagePreferencesRepository = storagePreferencesRepository,
            audioImporter = audioImporter,
        )
    }

    val downloaderRemoteClient: AuraDownloaderRemoteClient by lazy {
        AuraDownloaderRemoteClient(
            context = appContext,
            audioImporter = audioImporter,
            preferencesRepository = downloaderPreferencesRepository,
            onPendingImportsDrained = { systemStorageManager.syncJsonSnapshotsIfEnabled() },
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
 * Puente ligero de preferencias para el proceso aislado `:player`.
 * Lee la instantánea atómica en disco al arrancar y recibe cambios en caliente
 * vía Binder (`SessionCommand`) sin abrir nunca DataStore ni Room en paralelo.
 */
private class PlayerProcessTuningBridge(
    context: Context,
    private val outputMonitor: AuraAudioOutputMonitor,
    private val scope: CoroutineScope,
) : AuraPlaybackTuningProvider {
    private val initial = AuraPlayerConfigStore.readSnapshot(context)
    private val tuningFlow = MutableStateFlow(initial.playbackTuning)
    private val effectsFlow = MutableStateFlow(initial.audioEffects)
    private val headphonePrefsFlow = MutableStateFlow(initial.headphonePreferences)

    init {
        scope.launch {
            AuraRustBridge.setEqualizerConfig(
                initial.equalizer.isEnabled,
                initial.equalizer.bandGainsDb,
            )
            val route = outputMonitor.outputRoute.value
            val effective = HeadphoneRoutingHelper.resolveEffectiveRustEffects(
                effects = initial.audioEffects,
                outputRoute = route,
                prefs = initial.headphonePreferences,
            )
            AuraRustBridge.setAudioEffectsConfig(
                reverbEnabled = initial.audioEffects.reverbEnabled,
                reverbRoomSize = initial.audioEffects.reverbRoomSize,
                reverbDamping = initial.audioEffects.reverbDamping,
                reverbWetMix = initial.audioEffects.reverbWetMix,
                reverbWidth = initial.audioEffects.reverbWidth,
                slowedWarmthEnabled = initial.audioEffects.isSlowedWarmthActive,
                slowedWarmthAmount = initial.audioEffects.slowedWarmth,
                spatial8dEnabled = effective.spatial8dEnabled,
                spatial8dSpeed = initial.audioEffects.spatial8dSpeed,
                spatial8dDepth = initial.audioEffects.spatial8dDepth,
                crossfeedEnabled = effective.crossfeedEnabled,
                crossfeedStrength = initial.audioEffects.crossfeedStrength,
                crossfeedAngle = initial.audioEffects.crossfeedAngle,
            )
        }
    }

    override fun observePlaybackTuning(): Flow<PlaybackTuning> = tuningFlow

    override fun observeAudioEffects(): Flow<AudioEffectsState> = effectsFlow

    override fun observeHeadphonePreferences(): Flow<HeadphonePreferences> = headphonePrefsFlow

    override fun observeAudioOutputRoute(): Flow<AudioOutputRouteState> = outputMonitor.outputRoute

    override fun onSyncEqualizer(state: EqualizerState) {
        scope.launch {
            AuraRustBridge.setEqualizerConfig(state.isEnabled, state.bandGainsDb)
        }
    }

    override fun onSyncAudioEffects(state: AudioEffectsState) {
        effectsFlow.value = state.normalized()
    }

    override fun onSyncPlaybackTuning(tuning: PlaybackTuning) {
        tuningFlow.value = tuning.normalized()
    }

    override fun onSyncHeadphonePreferences(prefs: HeadphonePreferences) {
        headphonePrefsFlow.value = prefs.normalized()
    }
}

/**
 * Clase Application multi-proceso de Aura Music.
 *
 * Aísla estrictamente cada rol para minimizar la RAM mientras el usuario juega:
 * - Proceso principal (`MAIN`): UI Compose, Room, DataStore y Coil acotado al 8 % de heap con RGB_565.
 * - Proceso `:player`: únicamente `AuraPlaybackService`, ExoPlayer ligero, `AudioDeviceCallback` y DSP Rust.
 * - Proceso `:downloader`: únicamente Chaquopy / Python 3.11 / `yt-dlp` bajo demanda.
 */
class AuraApplication :
    Application(),
    AuraDownloadHost,
    AuraPlaybackTuningProvider,
    SingletonImageLoader.Factory {
    lateinit var container: AuraAppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var processRole: AuraProcessRole = AuraProcessRole.MAIN

    @Volatile
    private var playerProcessBridge: PlayerProcessTuningBridge? = null

    @Volatile
    private var playerProcessOutputMonitor: AuraAudioOutputMonitor? = null

    @Volatile
    private var cachedMainImageLoader: ImageLoader? = null

    private val isolatedDownloader: YtDlpAudioDownloader by lazy {
        YtDlpAudioDownloader(
            context = this,
            audioImporter = null,
            preferencesRepository = null,
            remoteWorkerMode = true,
            onPendingImportCreated = { descriptorPath ->
                AuraDownloadForegroundService.dispatchPendingImport(this, descriptorPath)
            },
            onUpdateCheckTimestamp = { epochMs ->
                AuraDownloadForegroundService.dispatchUpdateCheckTimestamp(epochMs)
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        processRole = AuraProcessRoleResolver.resolve(resolveCurrentProcessName())

        when (processRole) {
            AuraProcessRole.PLAYER -> {
                // El proceso :player NO toca Room, DataStore, Coil ni Python.
                val monitor = AuraAudioOutputMonitor(this).also { it.start() }
                playerProcessOutputMonitor = monitor
                playerProcessBridge = PlayerProcessTuningBridge(
                    context = this,
                    outputMonitor = monitor,
                    scope = appScope,
                )
            }

            AuraProcessRole.DOWNLOADER -> {
                // El proceso :downloader permanece mínimo hasta que `AuraDownloadForegroundService` inicia.
            }

            AuraProcessRole.MAIN -> {
                container = AuraAppContainer(this)
                container.audioOutputMonitor.start()
                appScope.launch {
                    runCatching {
                        val eq = container.equalizerPreferencesRepository.equalizerState.first()
                        val tuning = container.playbackPreferencesRepository.playbackTuning.first()
                        val fx = container.audioEffectsPreferencesRepository.audioEffectsState.first()
                        val hp = container.headphonePreferencesRepository.headphonePreferences.first()
                        AuraPlayerConfigStore.writeSnapshot(
                            context = this@AuraApplication,
                            snapshot = AuraPlayerConfigSnapshot(
                                equalizer = eq,
                                audioEffects = fx,
                                playbackTuning = tuning,
                                headphonePreferences = hp,
                            ),
                        )
                        AuraRustBridge.setEqualizerConfig(eq.isEnabled, eq.bandGainsDb)
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
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        if (processRole != AuraProcessRole.MAIN) {
            return ImageLoader.Builder(context)
                .memoryCache(null)
                .diskCache(null)
                .build()
        }
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, COIL_MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache(null)
            .allowRgb565(true)
            .crossfade(false)
            .build()
            .also { loader ->
                cachedMainImageLoader = loader
            }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (processRole != AuraProcessRole.MAIN) return

        // Ejecuta la purga selectiva de cachés visuales según los flags booleanos de MemoryTrimAction.
        val action: MemoryTrimAction = MemoryTrimPolicy.resolve(level)
        if (action.clearImageMemoryCache) {
            cachedMainImageLoader?.memoryCache?.clear()
        }
        if (action.clearDynamicColorCache) {
            clearCoverColorCache()
        }
        if (action.clearTransientControllerCaches && ::container.isInitialized) {
            container.playerController.trimMemoryIfDetached()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (processRole != AuraProcessRole.MAIN) return
        cachedMainImageLoader?.memoryCache?.clear()
        clearCoverColorCache()
        if (::container.isInitialized) {
            container.playerController.trimMemoryIfDetached()
        }
    }

    override fun provideAudioDownloader(): YtDlpAudioDownloader = isolatedDownloader

    override fun createDownloaderNotificationIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    override fun observePlaybackTuning(): Flow<PlaybackTuning> =
        playerProcessBridge?.observePlaybackTuning()
            ?: container.playbackPreferencesRepository.playbackTuning

    override fun observeAudioEffects(): Flow<AudioEffectsState> =
        playerProcessBridge?.observeAudioEffects()
            ?: container.audioEffectsPreferencesRepository.audioEffectsState

    override fun observeHeadphonePreferences(): Flow<HeadphonePreferences> =
        playerProcessBridge?.observeHeadphonePreferences()
            ?: container.headphonePreferencesRepository.headphonePreferences

    override fun observeAudioOutputRoute(): Flow<AudioOutputRouteState> =
        playerProcessBridge?.observeAudioOutputRoute()
            ?: container.audioOutputMonitor.outputRoute

    override fun onSyncEqualizer(state: EqualizerState) {
        playerProcessBridge?.onSyncEqualizer(state)
    }

    override fun onSyncAudioEffects(state: AudioEffectsState) {
        playerProcessBridge?.onSyncAudioEffects(state)
    }

    override fun onSyncPlaybackTuning(tuning: PlaybackTuning) {
        playerProcessBridge?.onSyncPlaybackTuning(tuning)
    }

    override fun onSyncHeadphonePreferences(prefs: HeadphonePreferences) {
        playerProcessBridge?.onSyncHeadphonePreferences(prefs)
    }

    override fun createOpenPlayerIntent(): Intent =
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }

    private fun resolveCurrentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val manager = getSystemService(ActivityManager::class.java) ?: return packageName
        return manager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: packageName
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "aura_open_player"
        private const val COIL_MEMORY_CACHE_PERCENT = 0.08
    }
}
