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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private enum class BindingState {
        DISCONNECTED,
        BINDING,
        CONNECTED,
    }

    private sealed interface PendingCommand {
        data class RefreshStatus(
            val autoUpdate: Boolean,
            val qualityKey: String,
        ) : PendingCommand

        data class CheckUpdates(
            val force: Boolean,
            val autoUpdate: Boolean,
            val lastCheckMs: Long,
        ) : PendingCommand

        data class InspectUrl(
            val cleanUrl: String,
            val autoUpdate: Boolean,
            val qualityKey: String,
            val lastCheckMs: Long,
        ) : PendingCommand

        data class SelectFormat(
            val formatId: String,
        ) : PendingCommand

        data class SetQuality(
            val qualityKey: String,
        ) : PendingCommand

        fun toMessage(): Message = when (this) {
            is RefreshStatus -> Message.obtain(null, AuraDownloaderIpc.MSG_REFRESH_STATUS).apply {
                data = Bundle().apply {
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, autoUpdate)
                    putString(AuraDownloaderIpc.KEY_QUALITY_KEY, qualityKey)
                }
            }

            is CheckUpdates -> Message.obtain(null, AuraDownloaderIpc.MSG_CHECK_UPDATES).apply {
                data = Bundle().apply {
                    putBoolean(AuraDownloaderIpc.KEY_FORCE, force)
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, autoUpdate)
                    putLong(AuraDownloaderIpc.KEY_LAST_CHECK_MS, lastCheckMs)
                }
            }

            is InspectUrl -> Message.obtain(null, AuraDownloaderIpc.MSG_INSPECT_URL).apply {
                data = Bundle().apply {
                    putString(AuraDownloaderIpc.KEY_URL, cleanUrl)
                    putBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, autoUpdate)
                    putString(AuraDownloaderIpc.KEY_QUALITY_KEY, qualityKey)
                    putLong(AuraDownloaderIpc.KEY_LAST_CHECK_MS, lastCheckMs)
                }
            }

            is SelectFormat -> Message.obtain(null, AuraDownloaderIpc.MSG_SELECT_FORMAT).apply {
                data = Bundle().apply {
                    putString(AuraDownloaderIpc.KEY_FORMAT_ID, formatId)
                }
            }

            is SetQuality -> Message.obtain(null, AuraDownloaderIpc.MSG_SET_QUALITY).apply {
                data = Bundle().apply {
                    putString(AuraDownloaderIpc.KEY_QUALITY_KEY, qualityKey)
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drainMutex = Mutex()
    private val connectionLock = Any()
    private val pendingCommands = mutableListOf<PendingCommand>()

    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _engineStatus = MutableStateFlow(DownloaderEngineStatus())
    val engineStatus: StateFlow<DownloaderEngineStatus> = _engineStatus.asStateFlow()

    @Volatile
    private var serviceMessenger: Messenger? = null

    @Volatile
    private var bindingState: BindingState = BindingState.DISCONNECTED

    @Volatile
    private var isBound = false

    @Volatile
    private var isScreenActive = false

    @Volatile
    private var pendingLocalUpdate = false

    @Volatile
    private var awaitingRemoteUpdateStart = false

    @Volatile
    private var reconnectAttempts = 0

    private var bindGeneration = 0L

    @Volatile
    private var connectTimeoutJob: Job? = null

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
                    val effectiveStatus = if (parsed.isUpdating) {
                        pendingLocalUpdate = false
                        awaitingRemoteUpdateStart = false
                        parsed
                    } else if (awaitingRemoteUpdateStart || pendingLocalUpdate) {
                        // Conserva versiones reales recibidas sin apagar el indicador mientras
                        // el comando MSG_CHECK_UPDATES arranca en el proceso :downloader.
                        parsed.copy(
                            isUpdating = true,
                            updateMessage = "Buscando actualizaciones de yt-dlp y yt-dlp-ejs...",
                        )
                    } else {
                        parsed
                    }
                    _engineStatus.value = effectiveStatus
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
            connectTimeoutJob?.cancel()
            connectTimeoutJob = null
            synchronized(connectionLock) {
                serviceMessenger = remote
                bindingState = BindingState.CONNECTED
                isBound = true
                reconnectAttempts = 0
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
                handleUnexpectedDisconnect("No se pudo registrar el cliente IPC del descargador.")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            handleUnexpectedDisconnect("Se desconectó el servicio aislado de descargas.")
        }

        override fun onBindingDied(name: ComponentName?) {
            handleUnexpectedDisconnect("El enlace con el proceso de descargas expiró.")
        }

        override fun onNullBinding(name: ComponentName?) {
            handleUnexpectedDisconnect(
                "El servicio de descargas no devolvió un enlace válido.",
                allowReconnect = false,
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
     */
    suspend fun refreshCachedStatus() = withContext(Dispatchers.IO) {
        val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
        val quality = preferencesRepository.audioDownloadQuality.first()
        val cached = AuraDownloaderIpc.readCachedEngineVersions(context)
        val quickJsVer = withContext(Dispatchers.Default) {
            AuraRustBridge.getQuickJsVersion()
        }

        val current = _engineStatus.value
        val hasLiveVersions = bindingState == BindingState.CONNECTED ||
            (current.ytDlpVersion != "Cargando..." &&
                current.ytDlpVersion != "Listo bajo demanda" &&
                current.ytDlpVersion != "No inicializado")

        if (cached != null) {
            _engineStatus.value = current.copy(
                pythonVersion = cached.pythonVersion,
                ytDlpVersion = cached.ytDlpVersion,
                ytDlpEjsVersion = cached.ytDlpEjsVersion,
                quickJsVersion = quickJsVer,
                ytDlpUpdatedAt = cached.ytDlpUpdatedAt,
                ytDlpEjsUpdatedAt = cached.ytDlpEjsUpdatedAt,
                autoUpdateEnabled = autoUpdate,
                preferredQuality = quality,
            )
        } else {
            _engineStatus.value = current.copy(
                pythonVersion = current.pythonVersion.ifBlank { "Python 3.11 (ligero)" },
                ytDlpVersion = if (hasLiveVersions) current.ytDlpVersion else "Listo bajo demanda",
                ytDlpEjsVersion = if (hasLiveVersions) current.ytDlpEjsVersion else "Listo bajo demanda",
                quickJsVersion = quickJsVer,
                autoUpdateEnabled = autoUpdate,
                preferredQuality = quality,
            )
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
            if (isScreenActive) {
                bindAndRefreshStatus()
            }
        } else {
            unregisterPendingImportReceiverIfNeeded()
            // Al pasar a segundo plano (p. ej. al abrir un juego), desenlaza siempre :downloader
            // para liberar Python; las descargas activas siguen vivas vía startForegroundService.
            unbindFromDownloaderService()
        }
    }

    fun setScreenActive(active: Boolean) {
        isScreenActive = active
        if (active) {
            registerPendingImportReceiverIfNeeded()
            val current = _engineStatus.value
            if (current.ytDlpVersion == "Listo bajo demanda" || current.ytDlpVersion == "No inicializado") {
                _engineStatus.value = current.copy(
                    ytDlpVersion = "Cargando...",
                    ytDlpEjsVersion = "Cargando...",
                )
            }
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
            enqueueOrSendCommand(
                PendingCommand.RefreshStatus(
                    autoUpdate = autoUpdate,
                    qualityKey = quality.key,
                ),
            )
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
            enqueueOrSendCommand(
                PendingCommand.InspectUrl(
                    cleanUrl = cleanUrl,
                    autoUpdate = autoUpdate,
                    qualityKey = quality.key,
                    lastCheckMs = lastCheck,
                ),
            )
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
        if (serviceMessenger != null) {
            enqueueOrSendCommand(PendingCommand.SelectFormat(formatId = formatId))
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
            enqueueOrSendCommand(PendingCommand.SetQuality(qualityKey = quality.key))
        }
    }

    fun checkAndUpdateEngines(force: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
            val lastCheck = preferencesRepository.lastUpdateCheckEpochMs.first()
            synchronized(connectionLock) {
                pendingLocalUpdate = true
                awaitingRemoteUpdateStart = true
            }
            _engineStatus.value = _engineStatus.value.copy(
                isUpdating = true,
                updateMessage = "Buscando actualizaciones de yt-dlp y yt-dlp-ejs...",
            )
            enqueueOrSendCommand(
                PendingCommand.CheckUpdates(
                    force = force,
                    autoUpdate = autoUpdate,
                    lastCheckMs = lastCheck,
                ),
            )
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

    private fun enqueueOrSendCommand(command: PendingCommand) {
        val remote = synchronized(connectionLock) {
            if (bindingState == BindingState.CONNECTED) serviceMessenger else null
        }
        if (remote != null && sendRawMessage(remote, command.toMessage())) {
            return
        }
        synchronized(connectionLock) {
            // Evita duplicar lecturas de estado idénticas en la cola pendiente.
            if (command is PendingCommand.RefreshStatus) {
                pendingCommands.removeAll { it is PendingCommand.RefreshStatus }
            }
            pendingCommands.add(command)
        }
        bindToDownloaderService()
    }

    private fun flushPendingCommands(remote: Messenger) {
        val batch = synchronized(connectionLock) {
            val copy = pendingCommands.toList()
            pendingCommands.clear()
            copy
        }
        for (index in batch.indices) {
            val cmd = batch[index]
            if (!sendRawMessage(remote, cmd.toMessage())) {
                synchronized(connectionLock) {
                    val remaining = batch.subList(index, batch.size)
                    pendingCommands.addAll(0, remaining)
                }
                handleUnexpectedDisconnect("Se interrumpió el envío de comandos al descargador.")
                return
            }
        }
    }

    private fun sendRawMessage(remote: Messenger, msg: Message): Boolean =
        runCatching {
            remote.send(msg)
            true
        }.getOrElse {
            synchronized(connectionLock) {
                serviceMessenger = null
                bindingState = BindingState.DISCONNECTED
            }
            false
        }

    private fun bindToDownloaderService() {
        val targetGeneration = synchronized(connectionLock) {
            if (bindingState == BindingState.CONNECTED || bindingState == BindingState.BINDING) {
                null
            } else {
                bindingState = BindingState.BINDING
                bindGeneration += 1L
                bindGeneration
            }
        } ?: return

        mainHandler.post {
            val stillNeeded = synchronized(connectionLock) {
                bindingState == BindingState.BINDING && bindGeneration == targetGeneration
            }
            if (!stillNeeded) return@post

            val intent = Intent(context, AuraDownloadForegroundService::class.java)
            val bound = runCatching {
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)

            if (bound) {
                synchronized(connectionLock) {
                    if (bindGeneration == targetGeneration) {
                        isBound = true
                    } else {
                        runCatching { context.unbindService(serviceConnection) }
                    }
                }
                if (synchronized(connectionLock) { bindGeneration == targetGeneration }) {
                    startConnectTimeoutWatchdog(targetGeneration)
                }
            } else {
                synchronized(connectionLock) {
                    if (bindGeneration == targetGeneration) {
                        isBound = false
                        bindingState = BindingState.DISCONNECTED
                        serviceMessenger = null
                    }
                }
                failPendingOperations(
                    "Android impidió enlazar con el servicio aislado de descargas.",
                )
            }
        }
    }

    private fun startConnectTimeoutWatchdog(targetGeneration: Long) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            val timedOut = synchronized(connectionLock) {
                bindingState == BindingState.BINDING && bindGeneration == targetGeneration
            }
            if (!timedOut) return@launch

            mainHandler.post {
                val stillBinding = synchronized(connectionLock) {
                    bindingState == BindingState.BINDING && bindGeneration == targetGeneration
                }
                if (!stillBinding) return@post
                unbindFromDownloaderService()
                failPendingOperations(
                    "El motor aislado de descargas tardó demasiado en responder. Pulsa de nuevo para reintentar.",
                )
            }
        }
    }

    private fun handleUnexpectedDisconnect(
        reason: String,
        allowReconnect: Boolean = true,
    ) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val hasPendingWork = synchronized(connectionLock) {
            serviceMessenger = null
            bindingState = BindingState.DISCONNECTED
            if (isBound) {
                runCatching { context.unbindService(serviceConnection) }
                isBound = false
            }
            pendingCommands.isNotEmpty()
        }

        if (allowReconnect && (hasPendingWork || isScreenActive) && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts += 1
            bindToDownloaderService()
            return
        }

        reconnectAttempts = 0
        failPendingOperations(reason)
    }

    private fun failPendingOperations(reason: String) {
        val hadPendingUpdate = synchronized(connectionLock) {
            val hadUpdate = pendingLocalUpdate ||
                awaitingRemoteUpdateStart ||
                pendingCommands.any { it is PendingCommand.CheckUpdates }
            pendingCommands.clear()
            pendingLocalUpdate = false
            awaitingRemoteUpdateStart = false
            hadUpdate
        }

        val currentStatus = _engineStatus.value
        if (hadPendingUpdate || currentStatus.isUpdating || currentStatus.ytDlpVersion == "Cargando...") {
            _engineStatus.value = currentStatus.copy(
                ytDlpVersion = if (currentStatus.ytDlpVersion == "Cargando...") {
                    "No inicializado"
                } else {
                    currentStatus.ytDlpVersion
                },
                ytDlpEjsVersion = if (currentStatus.ytDlpEjsVersion == "Cargando...") {
                    "No inicializado"
                } else {
                    currentStatus.ytDlpEjsVersion
                },
                isUpdating = false,
                updateMessage = reason,
            )
        }

        val currentDl = _downloadState.value
        if (currentDl.status == DownloadStatus.FETCHING_INFO ||
            currentDl.status == DownloadStatus.CHECKING_UPDATES
        ) {
            _downloadState.value = currentDl.copy(
                status = DownloadStatus.ERROR,
                errorMessage = reason,
                statusMessage = null,
            )
        }
    }

    private fun unbindFromDownloaderService() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        val (remote, wasBound) = synchronized(connectionLock) {
            bindGeneration += 1L
            pendingLocalUpdate = false
            awaitingRemoteUpdateStart = false
            pendingCommands.clear()
            val existingRemote = serviceMessenger
            val existingBound = isBound
            serviceMessenger = null
            bindingState = BindingState.DISCONNECTED
            isBound = false
            existingRemote to existingBound
        }
        if (remote == null && !wasBound) return
        mainHandler.post {
            remote?.let {
                sendRawMessage(
                    it,
                    Message.obtain(null, AuraDownloaderIpc.MSG_UNREGISTER_CLIENT).apply {
                        replyTo = replyMessenger
                    },
                )
            }
            if (wasBound) {
                runCatching { context.unbindService(serviceConnection) }
            }
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
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val MAX_RECONNECT_ATTEMPTS = 2
    }
}
