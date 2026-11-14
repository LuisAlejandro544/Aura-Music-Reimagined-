package com.auramusic.core.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Contrato desacoplado que implementa `AuraApplication` para que el servicio en primer plano
 * pueda obtener el descargador singleton y crear el Intent de apertura de la app sin
 * crear dependencias circulares entre `:core:downloader` y `:app`.
 */
interface AuraDownloadHost {
    fun provideAudioDownloader(): YtDlpAudioDownloader
    fun createDownloaderNotificationIntent(): Intent
}

/**
 * Servicio en primer plano (`foregroundServiceType = dataSync`) que mantiene activa la descarga
 * de audio con `yt-dlp` + `QuickJS-NG` incluso cuando el usuario sale de Aura Music, cambia
 * de aplicación o apaga la pantalla.
 */
class AuraDownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeDownloadJob: Job? = null
    private var stateObserverJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
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
                    )
                } else {
                    downloader.confirmPendingDownload(accentArgb = accentArgb)
                }
            } finally {
                releaseWakeLockSafely()
                stateObserverJob?.cancel()
                val finalState = downloader.downloadState.value
                postTerminalNotification(
                    state = finalState,
                    launchIntent = host.createDownloaderNotificationIntent(),
                    accentArgb = accentArgb,
                )
                ServiceCompat.stopForeground(this@AuraDownloadForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLockSafely()
        serviceScope.cancel()
        super.onDestroy()
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

        const val ACTION_START_CONFIRMED = "com.auramusic.downloader.ACTION_START_CONFIRMED"
        const val ACTION_START_URL = "com.auramusic.downloader.ACTION_START_URL"
        const val ACTION_CANCEL_DOWNLOAD = "com.auramusic.downloader.ACTION_CANCEL_DOWNLOAD"
        private const val EXTRA_ACCENT_ARGB = "extra_accent_argb"
        private const val EXTRA_URL = "extra_url"

        fun startConfirmedDownload(context: Context, accentArgb: Int) {
            val intent = Intent(context, AuraDownloadForegroundService::class.java).apply {
                action = ACTION_START_CONFIRMED
                putExtra(EXTRA_ACCENT_ARGB, accentArgb)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startUrlDownload(context: Context, rawUrl: String, accentArgb: Int) {
            val intent = Intent(context, AuraDownloadForegroundService::class.java).apply {
                action = ACTION_START_URL
                putExtra(EXTRA_URL, rawUrl)
                putExtra(EXTRA_ACCENT_ARGB, accentArgb)
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
