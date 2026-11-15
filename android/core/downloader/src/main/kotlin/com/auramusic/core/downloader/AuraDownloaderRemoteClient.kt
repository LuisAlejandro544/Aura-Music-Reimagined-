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
    private val bindingLock = Any()
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
    private var awaitingInspectAck = false

    @Volatile
    private var awaitingUpdateAck = false

    @Volatile
    private var lastAccentArgb: Int = DEFAULT_ACCENT_ARGB

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AuraDownloaderIpc.MSG_DOWNLOAD_STATE -> {
                    val raw = msg.data.getString(AuraDownloaderIpc.KEY_JSON) ?: return
                    val parsed = AuraDownloaderIpc.downloadStateFromJson(raw)
                    if (awaitingInspectAck && parsed.status == DownloadStatus.IDLE) {
                        // Evita que la instantánea inicial de MSG_REGISTER_CLIENT borre
                        // la transición local a FETCHING_INFO mientras llega MSG_INSPECT_URL.
                        return
                    }
                    if (parsed.status != DownloadStatus.IDLE) {
                        awaitingInspectAck = false
                    }
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
                    if (parsed.isUpdating) {
                        awaitingUpdateAck = false
                    }
                    val merged = if (awaitingUpdateAck && !parsed.isUpdating) {
                        // Conserva el spinner de actualización hasta que :downloader inicie el chequeo.
                        parsed.copy(
                            isUpdating = true,
                            updateMessage = _engineStatus.value.updateMessage,
                        )
                    } else {
                        parsed
                    }
                    _engineStatus.value = merged
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
            synchronized(bindingLock) {
                serviceMessenger = remote
                isBound = true
            }
            val registered = sendRawMessage(
                remote,
                Message.obtain(null, AuraDownloaderIpc.MSG_REGISTER_CLIENT).apply {
                    replyTo = replyMessenger
                },
            )
            if (registered) {
                flushPendingCommands(remote)
            } else {
                handleRemoteConnectionLost(
                    reason = "No se pudo registrar el cliente con el servicio :downloader.",
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleRemoteConnectionLost(
                reason = "El servicio de descargas (:downloader) se desconectó inesperadamente. Intenta de nuevo.",
            )
        }

        override fun onBindingDied(name: ComponentName?) {
            runCatching { context.unbindService(this) }
            handleRemoteConnectionLost(
                reason = "El proceso aislado (:downloader) se cerró antes de responder. Pulsa para reintentar.",
            )
        }

        override fun onNullBinding(name: ComponentName?) {
            runCatching { context.unbindService(this) }
            handleRemoteConnectionLost(
                reason = "El servicio :downloader rechazó el enlace IPC.",
            )
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
     * Si el proceso aislado ya reportó versiones reales en memoria, las preserva para evitar parpadeos.
     */
    suspend fun refreshCachedStatus() = withContext(Dispatchers.IO) {
        val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
        val quality = preferencesRepository.audioDownloadQuality.first()
        val cached = AuraDownloaderIpc.readCachedEngineVersions(context)
        val quickJsVer = withContext(Dispatchers.Default) {
            AuraRustBridge.getQuickJsVersion()
        }

        val current = _engineStatus.value
        val hasLiveYtDlp = current.ytDlpVersion !in PLACEHOLDER_ENGINE_STATES
        val hasLiveEjs = current.ytDlpEjsVersion !in PLACEHOLDER_ENGINE_STATES

        _engineStatus.value = current.copy(
            pythonVersion = cached?.pythonVersion ?: current.pythonVersion.ifBlank { "Python 3.11 (ligero)" },
            ytDlpVersion = cached?.ytDlpVersion ?: if (hasLiveYtDlp) current.ytDlpVersion else "Listo bajo demanda",
            ytDlpEjsVersion = cached?.ytDlpEjsVersion ?: if (hasLiveEjs) current.ytDlpEjsVersion else "Listo bajo demanda",
            quickJsVersion = quickJsVer,
            ytDlpUpdatedAt = cached?.ytDlpUpdatedAt ?: current.ytDlpUpdatedAt,
            ytDlpEjsUpdatedAt = cached?.ytDlpEjsUpdatedAt ?: current.ytDlpEjsUpdatedAt,
            autoUpdateEnabled = autoUpdate,
            preferredQuality = quality,
        )
    }

    /**
     * Actualiza el switch de auto-actualización en UI y lo sincroniza por IPC con `:downloader`
     * sin sobrescribir las versiones activas en pantalla.
     */
    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        preferencesRepository.setAutoUpdateEnabled(enabled)
        _engineStatus.value = _engineStatus.value.copy(autoUpdateEnabled = enabled)
        val hasConnection = synchronized(bindingLock) { serviceMessenger != null || isBound }
        if (hasConnection) {
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_SET_AUTO_UPDATE).apply {
                data = Bundle().apply {
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, enabled)
                }
            }
            enqueueOrSendMessage(msg)
        }
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
        val current = _engineStatus.value
        if (current.ytDlpVersion == "Listo bajo demanda" || current.ytDlpVersion == "No inicializado") {
            _engineStatus.value = current.copy(
                ytDlpVersion = "Cargando...",
                ytDlpEjsVersion = if (current.ytDlpEjsVersion == "Listo bajo demanda" || current.ytDlpEjsVersion == "No inicializado") {
                    "Cargando..."
                } else {
                    current.ytDlpEjsVersion
                },
            )
        }
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

        awaitingInspectAck = true
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

        val hasConnection = synchronized(bindingLock) { serviceMessenger != null || isBound }
        if (hasConnection) {
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
            awaitingUpdateAck = true
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
        val remote = synchronized(bindingLock) { serviceMessenger }
        if (remote != null && sendRawMessage(remote, msg)) {
            return
        }
        synchronized(bindingLock) {
            pendingCommands.add(msg)
        }
        bindToDownloaderService()
    }

    private fun flushPendingCommands(remote: Messenger) {
        val batch = synchronized(bindingLock) {
            val copy = pendingCommands.toList()
            pendingCommands.clear()
            copy
        }
        var failed = false
        for (msg in batch) {
            if (failed) {
                synchronized(bindingLock) { pendingCommands.add(msg) }
                continue
            }
            if (!sendRawMessage(remote, msg)) {
                failed = true
                synchronized(bindingLock) { pendingCommands.add(msg) }
            }
        }
        if (failed) {
            handleRemoteConnectionLost(
                reason = "Se perdió la conexión IPC al enviar comandos a :downloader.",
            )
        }
    }

    private fun sendRawMessage(remote: Messenger, msg: Message): Boolean =
        runCatching {
            remote.send(msg)
            true
        }.getOrElse {
            synchronized(bindingLock) {
                if (serviceMessenger === remote) {
                    serviceMessenger = null
                }
            }
            false
        }

    private fun bindToDownloaderService() {
        synchronized(bindingLock) {
            if (isBound) return
            isBound = true
        }
        val intent = Intent(context, AuraDownloadForegroundService::class.java)
        val boundOk = runCatching {
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)

        if (!boundOk) {
            synchronized(bindingLock) {
                isBound = false
                serviceMessenger = null
                pendingCommands.clear()
            }
            recoverPendingUiAfterBindFailure(
                reason = "No se pudo enlazar con el servicio de descargas (:downloader).",
            )
        }
    }

    private fun handleRemoteConnectionLost(reason: String) {
        synchronized(bindingLock) {
            serviceMessenger = null
            isBound = false
            pendingCommands.clear()
        }
        recoverPendingUiAfterBindFailure(reason)
    }

    private fun recoverPendingUiAfterBindFailure(reason: String) {
        awaitingInspectAck = false
        awaitingUpdateAck = false

        val currentDownload = _downloadState.value
        if (currentDownload.status == DownloadStatus.FETCHING_INFO ||
            currentDownload.status == DownloadStatus.CHECKING_UPDATES
        ) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = currentDownload.sourceUrl,
                title = currentDownload.title,
                errorMessage = reason,
            )
        }

        val currentEngine = _engineStatus.value
        val stalledVersions = currentEngine.ytDlpVersion == "Cargando..." ||
            currentEngine.ytDlpEjsVersion == "Cargando..."
        if (currentEngine.isUpdating || stalledVersions) {
            _engineStatus.value = currentEngine.copy(
                ytDlpVersion = if (currentEngine.ytDlpVersion == "Cargando...") {
                    "No inicializado"
                } else {
                    currentEngine.ytDlpVersion
                },
                ytDlpEjsVersion = if (currentEngine.ytDlpEjsVersion == "Cargando...") {
                    "No inicializado"
                } else {
                    currentEngine.ytDlpEjsVersion
                },
                isUpdating = false,
                updateMessage = reason,
            )
        }
    }

    private fun unbindFromDownloaderService() {
        awaitingInspectAck = false
        awaitingUpdateAck = false
        val remoteToNotify: Messenger?
        val shouldUnbind: Boolean
        synchronized(bindingLock) {
            remoteToNotify = serviceMessenger
            shouldUnbind = isBound
            serviceMessenger = null
            isBound = false
            pendingCommands.clear()
        }
        remoteToNotify?.let { remote ->
            sendRawMessage(
                remote,
                Message.obtain(null, AuraDownloaderIpc.MSG_UNREGISTER_CLIENT).apply {
                    replyTo = replyMessenger
                },
            )
        }
        if (shouldUnbind) {
            runCatching { context.unbindService(serviceConnection) }
        }
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
        private val PLACEHOLDER_ENGINE_STATES = setOf(
            "",
            "Cargando...",
            "Listo bajo demanda",
            "No inicializado",
            "no disponible",
        )
    }
}
