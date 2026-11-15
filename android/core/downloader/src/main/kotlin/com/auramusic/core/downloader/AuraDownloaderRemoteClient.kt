package com.auramusic.core.downloader

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.content.ContextCompat
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.DownloadFormatHelper
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cliente ligero que se ejecuta exclusivamente en el proceso principal (`MAIN`) para
 * comunicarse con el proceso aislado `:downloader` sin inicializar nunca Python/Chaquopy
 * en la memoria de la interfaz ni del reproductor.
 */
class AuraDownloaderRemoteClient(
    private val context: Context,
    private val audioImporter: AudioImporter,
    private val preferencesRepository: DownloaderPreferencesRepository,
    private val onPendingImportsDrained: (suspend () -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val drainMutex = Mutex()
    private val pendingCommands = mutableListOf<Message>()

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _engineStatus = MutableStateFlow(DownloaderEngineStatus())
    val engineStatus: StateFlow<DownloaderEngineStatus> = _engineStatus.asStateFlow()

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var isBound = false

    @Volatile
    private var isScreenActive = false

    @Volatile
    private var isForegroundReceiverRegistered = false

    @Volatile
    private var lastAccentArgb: Int = DEFAULT_ACCENT_ARGB

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AuraDownloaderIpc.MSG_DOWNLOAD_STATE -> {
                    val raw = msg.data.getString(AuraDownloaderIpc.KEY_JSON) ?: return
                    val parsed = AuraDownloaderIpc.downloadStateFromJson(raw)
                    val existingTrack = _downloadState.value.lastCompletedTrack
                    _downloadState.value = if (parsed.status == DownloadStatus.COMPLETED &&
                        parsed.lastCompletedTrack == null &&
                        existingTrack != null
                    ) {
                        parsed.copy(lastCompletedTrack = existingTrack)
                    } else {
                        parsed
                    }
                    if (parsed.status == DownloadStatus.COMPLETED) {
                        scope.launch { drainPendingImports(lastAccentArgb) }
                    }
                }

                AuraDownloaderIpc.MSG_ENGINE_STATUS -> {
                    val raw = msg.data.getString(AuraDownloaderIpc.KEY_JSON) ?: return
                    val parsed = AuraDownloaderIpc.engineStatusFromJson(raw)
                    _engineStatus.value = parsed
                }

                AuraDownloaderIpc.MSG_UPDATE_CHECK_TIMESTAMP -> {
                    val epochMs = msg.data.getLong(AuraDownloaderIpc.KEY_EPOCH_MS, 0L)
                    if (epochMs > 0L) {
                        scope.launch {
                            preferencesRepository.setLastUpdateCheckEpochMs(epochMs)
                        }
                    }
                }

                AuraDownloaderIpc.MSG_PENDING_IMPORT_SIGNAL -> {
                    scope.launch { drainPendingImports(lastAccentArgb) }
                }

                else -> super.handleMessage(msg)
            }
        }
    }

    private val replyMessenger = Messenger(replyHandler)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = Messenger(service)
            serviceMessenger = remote
            sendRawMessage(
                remote,
                Message.obtain(null, AuraDownloaderIpc.MSG_REGISTER_CLIENT).apply {
                    replyTo = replyMessenger
                },
            )
            flushPendingCommands(remote)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceMessenger = null
        }
    }

    private val pendingImportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AuraDownloaderIpc.ACTION_PENDING_IMPORT_READY) {
                scope.launch { drainPendingImports(lastAccentArgb) }
            }
        }
    }

    /**
     * Lee el estado de versiones almacenado en disco y preferencias DataStore sin arrancar Python.
     */
    suspend fun refreshCachedStatus() = withContext(Dispatchers.IO) {
        val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
        val quality = preferencesRepository.audioDownloadQuality.first()
        val cached = AuraDownloaderIpc.readCachedEngineVersions(context)
        val quickJsVer = withContext(Dispatchers.Default) {
            AuraRustBridge.getQuickJsVersion()
        }

        _engineStatus.value = _engineStatus.value.copy(
            pythonVersion = cached?.pythonVersion ?: "Python 3.11 (ligero)",
            ytDlpVersion = cached?.ytDlpVersion ?: "Listo bajo demanda",
            ytDlpEjsVersion = cached?.ytDlpEjsVersion ?: "Listo bajo demanda",
            quickJsVersion = quickJsVer,
            ytDlpUpdatedAt = cached?.ytDlpUpdatedAt,
            ytDlpEjsUpdatedAt = cached?.ytDlpEjsUpdatedAt,
            autoUpdateEnabled = autoUpdate,
            preferredQuality = quality,
        )
    }

    /**
     * Controla el ciclo de vida del enlace con `:downloader` y el receptor de descargas
     * terminadas para evitar mantener el proceso Python activo cuando el usuario juega.
     */
    fun setUiForeground(inForeground: Boolean) {
        if (inForeground) {
            registerPendingImportReceiverIfNeeded()
            scope.launch { drainPendingImports(lastAccentArgb) }
        } else {
            unregisterPendingImportReceiverIfNeeded()
            if (!isScreenActive) {
                unbindFromDownloaderService()
            }
        }
    }

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
        if (active) {
            registerPendingImportReceiverIfNeeded()
            bindAndRefreshStatus()
            scope.launch { drainPendingImports(lastAccentArgb) }
        } else {
            unbindFromDownloaderService()
        }
    }

    private fun bindAndRefreshStatus() {
        scope.launch(Dispatchers.IO) {
            val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
            val quality = preferencesRepository.audioDownloadQuality.first()
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_REFRESH_STATUS).apply {
                data = Bundle().apply {
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, autoUpdate)
                    putString(AuraDownloaderIpc.KEY_QUALITY_KEY, quality.key)
                }
            }
            enqueueOrSendMessage(msg)
        }
    }

    fun inspectAudioUrl(rawUrl: String) {
        val cleanUrl = YtDlpAudioDownloader.normalizeAndValidateUrl(rawUrl)
        if (cleanUrl == null) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = rawUrl,
                errorMessage = "Ingresa una URL válida que empiece por http:// o https://",
            )
            return
        }
        if (_downloadState.value.isBusy) return

        _downloadState.value = DownloadState(
            status = DownloadStatus.FETCHING_INFO,
            sourceUrl = cleanUrl,
            statusMessage = "Preparando motor aislado de descarga...",
        )

        scope.launch(Dispatchers.IO) {
            val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
            val quality = preferencesRepository.audioDownloadQuality.first()
            val lastCheck = preferencesRepository.lastUpdateCheckEpochMs.first()
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_INSPECT_URL).apply {
                data = Bundle().apply {
                    putString(AuraDownloaderIpc.KEY_URL, cleanUrl)
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, autoUpdate)
                    putString(AuraDownloaderIpc.KEY_QUALITY_KEY, quality.key)
                    putLong(AuraDownloaderIpc.KEY_LAST_CHECK_MS, lastCheck)
                }
            }
            enqueueOrSendMessage(msg)
        }
    }

    fun selectPreviewAudioFormat(formatId: String) {
        val current = _downloadState.value
        val preview = current.preview
        if (current.status == DownloadStatus.PREVIEW_READY && preview != null) {
            val updatedPreview = DownloadFormatHelper.selectOption(preview, formatId)
            _downloadState.value = current.copy(
                preview = updatedPreview,
                totalBytes = updatedPreview.estimatedSizeBytes,
            )
        }
        val msg = Message.obtain(null, AuraDownloaderIpc.MSG_SELECT_FORMAT).apply {
            data = Bundle().apply {
                putString(AuraDownloaderIpc.KEY_FORMAT_ID, formatId)
            }
        }
        if (serviceMessenger != null) {
            enqueueOrSendMessage(msg)
        }
    }

    suspend fun setPreferredAudioQuality(quality: AudioDownloadQuality) {
        preferencesRepository.setAudioDownloadQuality(quality)
        _engineStatus.value = _engineStatus.value.copy(preferredQuality = quality)

        val current = _downloadState.value
        val preview = current.preview
        if (current.status == DownloadStatus.PREVIEW_READY && preview != null && preview.availableFormats.isNotEmpty()) {
            val matched = DownloadFormatHelper.pickDefaultOption(preview.availableFormats, quality)
            if (matched != null) {
                val updatedPreview = DownloadFormatHelper.selectOption(preview, matched.formatId)
                _downloadState.value = current.copy(
                    preview = updatedPreview,
                    totalBytes = updatedPreview.estimatedSizeBytes,
                )
            }
        }

        if (serviceMessenger != null || isBound) {
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_SET_QUALITY).apply {
                data = Bundle().apply {
                    putString(AuraDownloaderIpc.KEY_QUALITY_KEY, quality.key)
                }
            }
            enqueueOrSendMessage(msg)
        }
    }

    fun checkAndUpdateEngines(force: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
            val lastCheck = preferencesRepository.lastUpdateCheckEpochMs.first()
            _engineStatus.value = _engineStatus.value.copy(
                isUpdating = true,
                updateMessage = "Buscando actualizaciones de yt-dlp y yt-dlp-ejs...",
            )
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_CHECK_UPDATES).apply {
                data = Bundle().apply {
                    putBoolean(AuraDownloaderIpc.KEY_FORCE, force)
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, autoUpdate)
                    putLong(AuraDownloaderIpc.KEY_LAST_CHECK_MS, lastCheck)
                }
            }
            enqueueOrSendMessage(msg)
        }
    }

    fun startBackgroundConfirmedDownload(accentArgb: Int) {
        lastAccentArgb = accentArgb
        val current = _downloadState.value
        val preview = current.preview ?: return
        if (current.isBusy) return
        registerPendingImportReceiverIfNeeded()
        scope.launch(Dispatchers.IO) {
            val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
            val quality = preferencesRepository.audioDownloadQuality.first()
            val lastCheck = preferencesRepository.lastUpdateCheckEpochMs.first()
            AuraDownloadForegroundService.startConfirmedDownload(
                context = context,
                accentArgb = accentArgb,
                preview = preview,
                preferredQuality = quality,
                autoUpdateEnabled = autoUpdate,
                lastCheckEpochMs = lastCheck,
            )
        }
    }

    fun startBackgroundUrlDownload(rawUrl: String, accentArgb: Int) {
        lastAccentArgb = accentArgb
        val cleanUrl = YtDlpAudioDownloader.normalizeAndValidateUrl(rawUrl)
        if (cleanUrl == null) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = rawUrl,
                errorMessage = "Ingresa una URL válida que empiece por http:// o https://",
            )
            return
        }
        if (_downloadState.value.isBusy) return
        registerPendingImportReceiverIfNeeded()
        scope.launch(Dispatchers.IO) {
            val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
            val quality = preferencesRepository.audioDownloadQuality.first()
            val lastCheck = preferencesRepository.lastUpdateCheckEpochMs.first()
            AuraDownloadForegroundService.startUrlDownload(
                context = context,
                rawUrl = cleanUrl,
                accentArgb = accentArgb,
                preferredQuality = quality,
                autoUpdateEnabled = autoUpdate,
                lastCheckEpochMs = lastCheck,
            )
        }
    }

    fun requestCancelDownload() {
        AuraDownloadForegroundService.cancelActiveDownload(context)
        val remote = serviceMessenger
        if (remote != null) {
            sendRawMessage(remote, Message.obtain(null, AuraDownloaderIpc.MSG_CANCEL_DOWNLOAD))
        }
    }

    fun clearDownloadStatus() {
        if (!_downloadState.value.isBusy) {
            _downloadState.value = DownloadState()
            serviceMessenger?.let { remote ->
                sendRawMessage(remote, Message.obtain(null, AuraDownloaderIpc.MSG_CLEAR_STATUS))
            }
        }
    }

    fun clearUpdateMessage() {
        _engineStatus.value = _engineStatus.value.copy(updateMessage = null)
        serviceMessenger?.let { remote ->
            sendRawMessage(remote, Message.obtain(null, AuraDownloaderIpc.MSG_CLEAR_UPDATE_MESSAGE))
        }
    }

    /**
     * Indexa en Room de forma única y atómica cualquier archivo de audio dejado por el proceso
     * aislado `:downloader` en `filesDir/pending_imports/`.
     */
    suspend fun drainPendingImports(fallbackAccentArgb: Int = lastAccentArgb): Int = drainMutex.withLock {
        withContext(Dispatchers.IO) {
            val pendingDir = AuraDownloaderIpc.pendingImportsDir(context)
            val descriptors = pendingDir.listFiles { file ->
                file.isFile && file.name.endsWith(".json")
            }?.sortedBy { it.name } ?: return@withContext 0

            var importedCount = 0
            for (descriptorFile in descriptors) {
                val pending = runCatching {
                    PendingDownloadedImport.fromJson(descriptorFile.readText())
                }.getOrNull()

                if (pending == null) {
                    descriptorFile.delete()
                    continue
                }

                val audioFile = File(pending.audioPath)
                if (!audioFile.exists() || audioFile.length() <= 0L) {
                    pending.thumbnailPath?.let { File(it).delete() }
                    descriptorFile.delete()
                    continue
                }

                val thumbFile = pending.thumbnailPath?.let(::File)?.takeIf { it.exists() }
                val importedTrack = audioImporter.importDownloadedAudioFile(
                    audioFile = audioFile,
                    fallbackTitle = pending.title,
                    fallbackArtist = pending.artist,
                    fallbackAlbum = pending.album,
                    fallbackDurationMs = pending.durationMs,
                    thumbnailFile = thumbFile,
                    accentArgb = pending.accentArgb.takeIf { it != 0 } ?: fallbackAccentArgb,
                )

                // Limpia siempre los temporales pendientes tras intentar la importación en Room.
                if (thumbFile != null && thumbFile.exists()) {
                    runCatching { thumbFile.delete() }
                }
                descriptorFile.delete()

                if (importedTrack != null) {
                    importedCount += 1
                    val current = _downloadState.value
                    if (current.status == DownloadStatus.COMPLETED || current.status == DownloadStatus.IMPORTING) {
                        _downloadState.value = current.copy(
                            status = DownloadStatus.COMPLETED,
                            title = importedTrack.title,
                            progressPercent = 100f,
                            statusMessage = "Agregada a tu biblioteca: ${importedTrack.title}",
                            lastCompletedTrack = importedTrack,
                        )
                    }
                }
            }
            if (importedCount > 0) {
                runCatching { onPendingImportsDrained?.invoke() }
            }
            importedCount
        }
    }

    private fun enqueueOrSendMessage(msg: Message) {
        val remote = serviceMessenger
        if (remote != null && sendRawMessage(remote, msg)) {
            return
        }
        synchronized(pendingCommands) {
            pendingCommands.add(msg)
        }
        bindToDownloaderService()
    }

    private fun flushPendingCommands(remote: Messenger) {
        val batch = synchronized(pendingCommands) {
            val copy = pendingCommands.toList()
            pendingCommands.clear()
            copy
        }
        batch.forEach { msg -> sendRawMessage(remote, msg) }
    }

    private fun sendRawMessage(remote: Messenger, msg: Message): Boolean =
        runCatching {
            remote.send(msg)
            true
        }.getOrElse {
            serviceMessenger = null
            false
        }

    private fun bindToDownloaderService() {
        if (isBound) return
        val intent = Intent(context, AuraDownloadForegroundService::class.java)
        isBound = runCatching {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    private fun unbindFromDownloaderService() {
        serviceMessenger?.let { remote ->
            sendRawMessage(
                remote,
                Message.obtain(null, AuraDownloaderIpc.MSG_UNREGISTER_CLIENT).apply {
                    replyTo = replyMessenger
                },
            )
        }
        if (isBound) {
            runCatching { context.unbindService(serviceConnection) }
            isBound = false
        }
        serviceMessenger = null
    }

    private fun registerPendingImportReceiverIfNeeded() {
        if (isForegroundReceiverRegistered) return
        val filter = IntentFilter(AuraDownloaderIpc.ACTION_PENDING_IMPORT_READY)
        runCatching {
            ContextCompat.registerReceiver(
                context,
                pendingImportReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            isForegroundReceiverRegistered = true
        }
    }

    private fun unregisterPendingImportReceiverIfNeeded() {
        if (!isForegroundReceiverRegistered) return
        runCatching { context.unregisterReceiver(pendingImportReceiver) }
        isForegroundReceiverRegistered = false
    }

    companion object {
        private const val DEFAULT_ACCENT_ARGB = 0xFFA78BFA.toInt()
    }
}
