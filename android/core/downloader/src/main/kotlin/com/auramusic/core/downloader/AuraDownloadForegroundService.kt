package com.auramusic.core.downloader

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.AuraProcessRole
import com.auramusic.core.model.AuraProcessRoleResolver
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Contrato desacoplado que implementa `AuraApplication` para que el servicio en primer plano
 * pueda obtener el descargador y crear el Intent de apertura de la app sin crear dependencias
 * circulares entre `:core:downloader` y `:app`.
 */
interface AuraDownloadHost {
    fun provideAudioDownloader(): YtDlpAudioDownloader

    fun createDownloaderNotificationIntent(): Intent
}

/**
 * Servicio en primer plano (`foregroundServiceType = dataSync`, proceso aislado `:downloader`)
 * que ejecuta Python 3.11 / `yt-dlp` / `QuickJS-NG` completamente separado del reproductor
 * y de la interfaz Compose. Cuando queda inactivo y sin clientes enlazados, se detiene y
 * libera el proceso `:downloader` para devolver toda la RAM nativa de Python al sistema.
 */
class AuraDownloadForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val boundClients = CopyOnWriteArraySet<Messenger>()
    private val commandMutex = Mutex()
    private val pendingCommandCount = AtomicInteger(0)

    private var activeDownloadJob: Job? = null
    private var notificationObserverJob: Job? = null
    private var ipcDownloadObserverJob: Job? = null
    private var ipcEngineObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isServiceBound = false

    @Volatile
    private var idleTerminationArmed = false

    private val idleShutdownRunnable = Runnable {
        if (!isServiceBound &&
            boundClients.isEmpty() &&
            activeDownloadJob?.isActive != true &&
            pendingCommandCount.get() <= 0
        ) {
            idleTerminationArmed = true
            stopSelf()
        }
    }

    private val incomingHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val host = applicationContext as? AuraDownloadHost ?: return
            val downloader = host.provideAudioDownloader()
            val data = msg.data

            when (msg.what) {
                AuraDownloaderIpc.MSG_REGISTER_CLIENT -> {
                    cancelIdleShutdown()
                    msg.replyTo?.let { client ->
                        boundClients.add(client)
                        sendToClient(
                            client = client,
                            what = AuraDownloaderIpc.MSG_DOWNLOAD_STATE,
                            json = AuraDownloaderIpc.downloadStateToJson(downloader.downloadState.value),
                            isInitialSnapshot = true,
                        )
                        sendToClient(
                            client = client,
                            what = AuraDownloaderIpc.MSG_ENGINE_STATUS,
                            json = AuraDownloaderIpc.engineStatusToJson(downloader.engineStatus.value),
                        )
                    }
                }

                AuraDownloaderIpc.MSG_UNREGISTER_CLIENT -> {
                    msg.replyTo?.let { boundClients.remove(it) }
                    scheduleIdleShutdownIfEligible(downloader)
                }

                AuraDownloaderIpc.MSG_REFRESH_STATUS -> {
                    cancelIdleShutdown()
                    val autoUpdate = if (data.containsKey(AuraDownloaderIpc.KEY_AUTO_UPDATE)) {
                        data.getBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE)
                    } else {
                        null
                    }
                    val quality = data.getString(AuraDownloaderIpc.KEY_QUALITY_KEY)
                        ?.let(AudioDownloadQuality::fromKey)
                    launchCommand(downloader) {
                        downloader.refreshEngineStatus(
                            autoUpdateOverride = autoUpdate,
                            qualityOverride = quality,
                        )
                    }
                }

                AuraDownloaderIpc.MSG_CHECK_UPDATES -> {
                    cancelIdleShutdown()
                    val force = data.getBoolean(AuraDownloaderIpc.KEY_FORCE, true)
                    val autoUpdate = if (data.containsKey(AuraDownloaderIpc.KEY_AUTO_UPDATE)) {
                        data.getBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE)
                    } else {
                        null
                    }
                    val lastCheck = if (data.containsKey(AuraDownloaderIpc.KEY_LAST_CHECK_MS)) {
                        data.getLong(AuraDownloaderIpc.KEY_LAST_CHECK_MS)
                    } else {
                        null
                    }
                    launchCommand(downloader) {
                        downloader.checkAndUpdateEngines(
                            force = force,
                            autoUpdateOverride = autoUpdate,
                            lastCheckOverride = lastCheck,
                        )
                    }
                }

                AuraDownloaderIpc.MSG_INSPECT_URL -> {
                    cancelIdleShutdown()
                    val rawUrl = data.getString(AuraDownloaderIpc.KEY_URL).orEmpty()
                    val autoUpdate = if (data.containsKey(AuraDownloaderIpc.KEY_AUTO_UPDATE)) {
                        data.getBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE)
                    } else {
                        null
                    }
                    val quality = data.getString(AuraDownloaderIpc.KEY_QUALITY_KEY)
                        ?.let(AudioDownloadQuality::fromKey)
                    val lastCheck = if (data.containsKey(AuraDownloaderIpc.KEY_LAST_CHECK_MS)) {
                        data.getLong(AuraDownloaderIpc.KEY_LAST_CHECK_MS)
                    } else {
                        null
                    }
                    launchCommand(downloader) {
                        downloader.inspectAudioUrl(
                            rawUrl = rawUrl,
                            autoUpdateOverride = autoUpdate,
                            qualityOverride = quality,
                            lastCheckOverride = lastCheck,
                        )
                    }
                }

                AuraDownloaderIpc.MSG_SELECT_FORMAT -> {
                    val formatId = data.getString(AuraDownloaderIpc.KEY_FORMAT_ID).orEmpty()
                    if (formatId.isNotBlank()) {
                        downloader.selectPreviewAudioFormat(formatId)
                    }
                }

                AuraDownloaderIpc.MSG_SET_QUALITY -> {
                    val quality = AudioDownloadQuality.fromKey(
                        data.getString(AuraDownloaderIpc.KEY_QUALITY_KEY),
                    )
                    launchCommand(downloader) {
                        downloader.setPreferredAudioQuality(quality)
                    }
                }

                AuraDownloaderIpc.MSG_CLEAR_STATUS -> downloader.clearDownloadStatus()
                AuraDownloaderIpc.MSG_CLEAR_UPDATE_MESSAGE -> downloader.clearUpdateMessage()
                AuraDownloaderIpc.MSG_CANCEL_DOWNLOAD -> downloader.requestCancelDownload()
                AuraDownloaderIpc.MSG_SET_AUTO_UPDATE -> {
                    val autoUpdate = data.getBoolean(AuraDownloaderIpc.KEY_AUTO_UPDATE, true)
                    downloader.applyRemotePreferences(autoUpdateEnabled = autoUpdate)
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    private val incomingMessenger = Messenger(incomingHandler)

    /**
     * Encola y ejecuta secuencialmente los comandos IPC usando [commandMutex] para que ninguna
     * petición (`MSG_INSPECT_URL`, `MSG_CHECK_UPDATES`, etc.) se descarte mientras Chaquopy
     * termina su inicialización bajo demanda.
     */
    private fun launchCommand(
        downloader: YtDlpAudioDownloader,
        block: suspend () -> Unit,
    ) {
        pendingCommandCount.incrementAndGet()
        serviceScope.launch {
            try {
                commandMutex.withLock {
                    block()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Los fallos controlados ya se propagan por DownloadState/EngineStatus; evita cerrar el worker IPC.
            } finally {
                pendingCommandCount.decrementAndGet()
                mainHandler.post {
                    scheduleIdleShutdownIfEligible(downloader)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        isServiceBound = true
        cancelIdleShutdown()
        startIpcStateBroadcastIfNeeded()
        return incomingMessenger.binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        isServiceBound = true
        cancelIdleShutdown()
        startIpcStateBroadcastIfNeeded()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceBound = false
        boundClients.clear()
        val downloader = (applicationContext as? AuraDownloadHost)?.provideAudioDownloader()
        scheduleIdleShutdownIfEligible(downloader)
        return true
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        ensureNotificationChannels()
        startIpcStateBroadcastIfNeeded()
    }

    private fun startIpcStateBroadcastIfNeeded() {
        val downloader = (applicationContext as? AuraDownloadHost)?.provideAudioDownloader() ?: return
        if (ipcDownloadObserverJob?.isActive != true) {
            ipcDownloadObserverJob = serviceScope.launch {
                // Usa collect estricto para no cancelar entregas intermedias o terminales hacia MAIN.
                downloader.downloadState.collect { state ->
                    broadcastDownloadStateToClients(state)
                }
            }
        }
        if (ipcEngineObserverJob?.isActive != true) {
            ipcEngineObserverJob = serviceScope.launch {
                downloader.engineStatus.collect { status ->
                    broadcastEngineStatusToClients(status)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cancelIdleShutdown()
        val host = applicationContext as? AuraDownloadHost
        if (host == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val downloader = host.provideAudioDownloader()
        val action = intent?.action

        if (action == ACTION_CANCEL_DOWNLOAD) {
            downloader.requestCancelDownload()
            return START_NOT_STICKY
        }

        val accentArgb = intent?.getIntExtra(EXTRA_ACCENT_ARGB, DEFAULT_ACCENT_ARGB)
            ?: DEFAULT_ACCENT_ARGB
        val rawUrl = intent?.getStringExtra(EXTRA_URL)
        val quality = intent?.getStringExtra(EXTRA_QUALITY_KEY)?.let(AudioDownloadQuality::fromKey)
        val autoUpdate = if (intent?.hasExtra(EXTRA_AUTO_UPDATE) == true) {
            intent.getBooleanExtra(EXTRA_AUTO_UPDATE, true)
        } else {
            null
        }
        val lastCheckMs = if (intent?.hasExtra(EXTRA_LAST_CHECK_MS) == true) {
            intent.getLongExtra(EXTRA_LAST_CHECK_MS, 0L)
        } else {
            null
        }
        val preview = intent?.getStringExtra(EXTRA_PREVIEW_JSON)?.let(AuraDownloaderIpc::previewFromJson)

        downloader.applyRemotePreferences(
            autoUpdateEnabled = autoUpdate,
            preferredQuality = quality,
            lastUpdateCheckEpochMs = lastCheckMs,
        )
        downloader.restorePreviewIfNeeded(preview)

        val initialNotification = buildProgressNotification(
            state = downloader.downloadState.value,
            launchIntent = host.createDownloaderNotificationIntent(),
            accentArgb = accentArgb,
        )
        startForegroundCompat(initialNotification)

        if (activeDownloadJob?.isActive == true) {
            return START_NOT_STICKY
        }

        acquireWakeLockSafely()

        notificationObserverJob?.cancel()
        notificationObserverJob = serviceScope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            downloader.downloadState.collectLatest { state ->
                if (state.isBusy) {
                    manager?.notify(
                        PROGRESS_NOTIFICATION_ID,
                        buildProgressNotification(
                            state = state,
                            launchIntent = host.createDownloaderNotificationIntent(),
                            accentArgb = accentArgb,
                        ),
                    )
                }
            }
        }

        activeDownloadJob = serviceScope.launch {
            try {
                if (action == ACTION_START_URL && !rawUrl.isNullOrBlank()) {
                    downloader.downloadAudio(
                        rawUrl = rawUrl,
                        accentArgb = accentArgb,
                        preview = null,
                        autoUpdateOverride = autoUpdate,
                        qualityOverride = quality,
                        lastCheckOverride = lastCheckMs,
                    )
                } else {
                    downloader.confirmPendingDownload(
                        accentArgb = accentArgb,
                        autoUpdateOverride = autoUpdate,
                        qualityOverride = quality,
                        lastCheckOverride = lastCheckMs,
                    )
                }
            } finally {
                releaseWakeLockSafely()
                notificationObserverJob?.cancel()
                val finalState = downloader.downloadState.value
                postTerminalNotification(
                    state = finalState,
                    launchIntent = host.createDownloaderNotificationIntent(),
                    accentArgb = accentArgb,
                )
                ServiceCompat.stopForeground(
                    this@AuraDownloadForegroundService,
                    ServiceCompat.STOP_FOREGROUND_REMOVE,
                )
                mainHandler.post {
                    if (!isServiceBound && boundClients.isEmpty()) {
                        idleTerminationArmed = true
                        stopSelf()
                    } else {
                        scheduleIdleShutdownIfEligible(downloader)
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (activeServiceInstance === this) {
            activeServiceInstance = null
        }
        cancelIdleShutdown()
        releaseWakeLockSafely()
        serviceScope.cancel()
        super.onDestroy()

        if (idleTerminationArmed &&
            !isServiceBound &&
            boundClients.isEmpty() &&
            isIsolatedDownloaderProcess()
        ) {
            // Devuelve al sistema operativo el heap nativo de CPython / libpython cuando
            // el descargador terminó su trabajo y no hay UI escuchando.
            Process.killProcess(Process.myPid())
        }
    }

    private fun isIsolatedDownloaderProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            ""
        }
        return AuraProcessRoleResolver.resolve(processName) == AuraProcessRole.DOWNLOADER
    }

    private fun cancelIdleShutdown() {
        idleTerminationArmed = false
        mainHandler.removeCallbacks(idleShutdownRunnable)
    }

    private fun scheduleIdleShutdownIfEligible(downloader: YtDlpAudioDownloader?) {
        mainHandler.removeCallbacks(idleShutdownRunnable)
        val isBusy = downloader?.downloadState?.value?.isBusy == true ||
            activeDownloadJob?.isActive == true ||
            pendingCommandCount.get() > 0
        if (!isServiceBound && boundClients.isEmpty() && !isBusy) {
            mainHandler.postDelayed(idleShutdownRunnable, IDLE_SHUTDOWN_DELAY_MS)
        }
    }

    private fun broadcastDownloadStateToClients(state: DownloadState) {
        if (boundClients.isEmpty()) return
        val json = AuraDownloaderIpc.downloadStateToJson(state)
        boundClients.forEach { client ->
            if (!sendToClient(client, AuraDownloaderIpc.MSG_DOWNLOAD_STATE, json)) {
                boundClients.remove(client)
            }
        }
    }

    private fun broadcastEngineStatusToClients(status: DownloaderEngineStatus) {
        if (boundClients.isEmpty()) return
        val json = AuraDownloaderIpc.engineStatusToJson(status)
        boundClients.forEach { client ->
            if (!sendToClient(client, AuraDownloaderIpc.MSG_ENGINE_STATUS, json)) {
                boundClients.remove(client)
            }
        }
    }

    internal fun notifyPendingImportToClients(descriptorPath: String) {
        boundClients.forEach { client ->
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_PENDING_IMPORT_SIGNAL).apply {
                data = Bundle().apply {
                    putString(AuraDownloaderIpc.KEY_FILE_PATH, descriptorPath)
                }
            }
            if (runCatching { client.send(msg) }.isFailure) {
                boundClients.remove(client)
            }
        }
    }

    internal fun notifyUpdateTimestampToClients(epochMs: Long) {
        boundClients.forEach { client ->
            val msg = Message.obtain(null, AuraDownloaderIpc.MSG_UPDATE_CHECK_TIMESTAMP).apply {
                data = Bundle().apply {
                    putLong(AuraDownloaderIpc.KEY_EPOCH_MS, epochMs)
                }
            }
            if (runCatching { client.send(msg) }.isFailure) {
                boundClients.remove(client)
            }
        }
    }

    private fun sendToClient(
        client: Messenger,
        what: Int,
        json: String,
        isInitialSnapshot: Boolean = false,
    ): Boolean {
        val msg = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putString(AuraDownloaderIpc.KEY_JSON, json)
                if (isInitialSnapshot) {
                    putBoolean(AuraDownloaderIpc.KEY_INITIAL_SNAPSHOT, true)
                }
            }
        }
        return runCatching { client.send(msg) }.isSuccess
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                PROGRESS_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS_ID,
            "Descargas en curso",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progreso en segundo plano de descargas de audio con yt-dlp"
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS_ID,
            "Estado de descargas",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Avisos cuando una descarga de audio termina o falla"
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    private fun buildProgressNotification(
        state: DownloadState,
        launchIntent: Intent,
        accentArgb: Int = DEFAULT_ACCENT_ARGB,
    ): Notification {
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            101,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = Intent(this, AuraDownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            102,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = state.title?.takeIf { it.isNotBlank() } ?: "Descargando audio"
        val percentInt = state.progressPercent.toInt().coerceIn(0, 100)
        val indeterminate = state.status != DownloadStatus.DOWNLOADING || state.totalBytes <= 0L

        val contentText = when (state.status) {
            DownloadStatus.CHECKING_UPDATES -> "Comprobando parches de yt-dlp..."
            DownloadStatus.FETCHING_INFO -> "Analizando enlace..."
            DownloadStatus.RESOLVING -> "Resolviendo audio con QuickJS-NG..."
            DownloadStatus.DOWNLOADING -> buildDownloadingText(state)
            DownloadStatus.IMPORTING -> "Leyendo metadatos en Rust e importando..."
            else -> state.statusMessage ?: "Preparando descarga en segundo plano..."
        }

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS_ID)
            .setSmallIcon(R.drawable.ic_aura_download_notification)
            .setColor(accentArgb)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapPendingIntent)
            .setProgress(100, percentInt, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancelar",
                cancelPendingIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun postTerminalNotification(
        state: DownloadState,
        launchIntent: Intent,
        accentArgb: Int = DEFAULT_ACCENT_ARGB,
    ) {
        if (state.status != DownloadStatus.COMPLETED &&
            state.status != DownloadStatus.ERROR &&
            state.status != DownloadStatus.CANCELLED
        ) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            103,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, message) = when (state.status) {
            DownloadStatus.COMPLETED -> Pair(
                "Descarga completada",
                state.lastCompletedTrack?.title ?: state.title ?: "Audio añadido a tu biblioteca",
            )
            DownloadStatus.CANCELLED -> Pair(
                "Descarga cancelada",
                state.title ?: "La descarga en segundo plano fue cancelada",
            )
            else -> Pair(
                "Error en la descarga",
                state.errorMessage ?: "No se pudo completar la descarga",
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setSmallIcon(R.drawable.ic_aura_download_notification)
            .setColor(accentArgb)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .build()

        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLockSafely() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java) ?: return
            val lock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AuraMusic::YtDlpBackgroundDownload",
            ).apply {
                setReferenceCounted(false)
                acquire(30L * 60L * 1000L)
            }
            wakeLock = lock
        }
    }

    private fun releaseWakeLockSafely() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        }
    }

    private fun buildDownloadingText(state: DownloadState): String {
        val pct = state.progressPercent.coerceIn(0f, 100f)
        val dl = formatCompactBytes(state.downloadedBytes)
        val speed = if (state.speedBytesPerSec > 0L) {
            " • ${formatCompactBytes(state.speedBytesPerSec)}/s"
        } else {
            ""
        }
        return if (state.totalBytes > 0L) {
            val total = formatCompactBytes(state.totalBytes)
            String.format(Locale.US, "%.0f%% (%s / %s)%s", pct, dl, total, speed)
        } else {
            "$dl descargados$speed"
        }
    }

    private fun formatCompactBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", safe / (1024f * 1024f))
            safe >= 1024L -> String.format(Locale.US, "%.0f KB", safe / 1024f)
            else -> "$safe B"
        }
    }

    companion object {
        private const val CHANNEL_PROGRESS_ID = "aura_downloads_progress"
        private const val CHANNEL_ALERTS_ID = "aura_downloads_alerts"
        private const val PROGRESS_NOTIFICATION_ID = 4201
        private const val RESULT_NOTIFICATION_ID = 4202
        private const val DEFAULT_ACCENT_ARGB = 0xFFA78BFA.toInt()
        private const val IDLE_SHUTDOWN_DELAY_MS = 5_000L

        @Volatile
        private var activeServiceInstance: AuraDownloadForegroundService? = null

        const val ACTION_START_CONFIRMED = "com.auramusic.downloader.ACTION_START_CONFIRMED"
        const val ACTION_START_URL = "com.auramusic.downloader.ACTION_START_URL"
        const val ACTION_CANCEL_DOWNLOAD = "com.auramusic.downloader.ACTION_CANCEL_DOWNLOAD"
        private const val EXTRA_ACCENT_ARGB = "extra_accent_argb"
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_QUALITY_KEY = "extra_quality_key"
        private const val EXTRA_AUTO_UPDATE = "extra_auto_update"
        private const val EXTRA_LAST_CHECK_MS = "extra_last_check_ms"
        private const val EXTRA_PREVIEW_JSON = "extra_preview_json"

        fun dispatchPendingImport(context: Context, descriptorPath: String) {
            val broadcastIntent = Intent(AuraDownloaderIpc.ACTION_PENDING_IMPORT_READY).apply {
                setPackage(context.packageName)
                putExtra(AuraDownloaderIpc.KEY_FILE_PATH, descriptorPath)
            }
            runCatching { context.sendBroadcast(broadcastIntent) }
            activeServiceInstance?.notifyPendingImportToClients(descriptorPath)
        }

        fun dispatchUpdateCheckTimestamp(epochMs: Long) {
            activeServiceInstance?.notifyUpdateTimestampToClients(epochMs)
        }

        fun startConfirmedDownload(
            context: Context,
            accentArgb: Int,
            preview: com.auramusic.core.model.DownloadPreview? = null,
            preferredQuality: AudioDownloadQuality? = null,
            autoUpdateEnabled: Boolean? = null,
            lastCheckEpochMs: Long? = null,
        ) {
            val intent = Intent(context, AuraDownloadForegroundService::class.java).apply {
                action = ACTION_START_CONFIRMED
                putExtra(EXTRA_ACCENT_ARGB, accentArgb)
                preferredQuality?.let { putExtra(EXTRA_QUALITY_KEY, it.key) }
                autoUpdateEnabled?.let { putExtra(EXTRA_AUTO_UPDATE, it) }
                lastCheckEpochMs?.let { putExtra(EXTRA_LAST_CHECK_MS, it) }
                preview?.let { putExtra(EXTRA_PREVIEW_JSON, AuraDownloaderIpc.previewToJson(it)) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startUrlDownload(
            context: Context,
            rawUrl: String,
            accentArgb: Int,
            preferredQuality: AudioDownloadQuality? = null,
            autoUpdateEnabled: Boolean? = null,
            lastCheckEpochMs: Long? = null,
        ) {
            val intent = Intent(context, AuraDownloadForegroundService::class.java).apply {
                action = ACTION_START_URL
                putExtra(EXTRA_URL, rawUrl)
                putExtra(EXTRA_ACCENT_ARGB, accentArgb)
                preferredQuality?.let { putExtra(EXTRA_QUALITY_KEY, it.key) }
                autoUpdateEnabled?.let { putExtra(EXTRA_AUTO_UPDATE, it) }
                lastCheckEpochMs?.let { putExtra(EXTRA_LAST_CHECK_MS, it) }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelActiveDownload(context: Context) {
            val intent = Intent(context, AuraDownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            runCatching { context.startService(intent) }
        }
    }
}
