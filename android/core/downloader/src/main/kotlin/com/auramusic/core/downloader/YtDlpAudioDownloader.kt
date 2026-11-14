package com.auramusic.core.downloader

import android.content.Context
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.rust.AuraRustBridge
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Coordinador Kotlin del motor de descargas de audio de Aura Music.
 *
 * Conecta:
 * - Chaquopy 17 (Python 3.11 ligero compilado a bytecode .pyc).
 * - `yt-dlp` y `yt-dlp-ejs` con auto-actualización en runtime desde PyPI + verificación SHA-256.
 * - `libaura_qjs.so` (QuickJS-NG compilado en Rust para 32 y 64 bits).
 * - [AudioImporter] + Rust (`lofty`) para indexar automáticamente el audio en Room.
 */
class YtDlpAudioDownloader(
    private val context: Context,
    private val audioImporter: AudioImporter,
    private val preferencesRepository: DownloaderPreferencesRepository,
) {
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _engineStatus = MutableStateFlow(DownloaderEngineStatus())
    val engineStatus: StateFlow<DownloaderEngineStatus> = _engineStatus.asStateFlow()

    private val pythonMutex = Mutex()

    private val updatesDir: File
        get() = File(context.filesDir, "py_updates").apply { mkdirs() }

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    /**
     * Inicializa Chaquopy (si aún no está iniciado) y lee las versiones activas
     * de Python, yt-dlp, yt-dlp-ejs y QuickJS-NG fuera del hilo principal.
     */
    suspend fun refreshEngineStatus() = withContext(Dispatchers.IO) {
        val autoUpdate = preferencesRepository.autoUpdateEnabled.first()
        val quickJsVer = withContext(Dispatchers.Default) {
            AuraRustBridge.getQuickJsVersion()
        }

        val pyInfo = runCatching {
            pythonMutex.withLock {
                val py = ensurePythonStarted()
                val mod = py.getModule("aura_ytdlp")
                val rawJson = mod.callAttr("get_engine_versions", updatesDir.absolutePath).toString()
                JSONObject(rawJson)
            }
        }.getOrNull()

        _engineStatus.value = _engineStatus.value.copy(
            pythonVersion = pyInfo?.optString("python_version")?.takeIf { it.isNotBlank() }
                ?: "Python 3.11 (ligero)",
            ytDlpVersion = pyInfo?.optString("yt_dlp_version")?.takeIf { it.isNotBlank() }
                ?: "No inicializado",
            ytDlpEjsVersion = pyInfo?.optString("yt_dlp_ejs_version")?.takeIf { it.isNotBlank() }
                ?: "No inicializado",
            quickJsVersion = quickJsVer,
            ytDlpUpdatedAt = pyInfo?.optString("yt_dlp_updated_at")?.takeIf { it.isNotBlank() },
            ytDlpEjsUpdatedAt = pyInfo?.optString("yt_dlp_ejs_updated_at")?.takeIf { it.isNotBlank() },
            autoUpdateEnabled = autoUpdate,
        )
    }

    /**
     * Actualiza `yt-dlp` y `yt-dlp-ejs` independientemente desde PyPI sin recompilar el APK.
     */
    suspend fun checkAndUpdateEngines(force: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force) {
            val autoEnabled = preferencesRepository.autoUpdateEnabled.first()
            val lastCheck = preferencesRepository.lastUpdateCheckEpochMs.first()
            val twelveHoursMs = 12L * 60L * 60L * 1000L
            if (!autoEnabled || (now - lastCheck) < twelveHoursMs) {
                return@withContext true
            }
        }

        _engineStatus.value = _engineStatus.value.copy(
            isUpdating = true,
            updateMessage = "Buscando actualizaciones de yt-dlp y yt-dlp-ejs...",
        )

        val result = runCatching {
            pythonMutex.withLock {
                val py = ensurePythonStarted()
                val mod = py.getModule("aura_ytdlp")
                val rawJson = mod.callAttr("update_packages", updatesDir.absolutePath).toString()
                JSONObject(rawJson)
            }
        }

        preferencesRepository.setLastUpdateCheckEpochMs(now)

        return@withContext result.fold(
            onSuccess = { json ->
                val ok = json.optBoolean("ok", true)
                _engineStatus.value = _engineStatus.value.copy(
                    pythonVersion = json.optString("python_version", _engineStatus.value.pythonVersion),
                    ytDlpVersion = json.optString("yt_dlp_version", _engineStatus.value.ytDlpVersion),
                    ytDlpEjsVersion = json.optString("yt_dlp_ejs_version", _engineStatus.value.ytDlpEjsVersion),
                    ytDlpUpdatedAt = json.optString("yt_dlp_updated_at").takeIf { it.isNotBlank() },
                    ytDlpEjsUpdatedAt = json.optString("yt_dlp_ejs_updated_at").takeIf { it.isNotBlank() },
                    isUpdating = false,
                    updateMessage = json.optString("message").takeIf { it.isNotBlank() },
                )
                ok
            },
            onFailure = { err ->
                _engineStatus.value = _engineStatus.value.copy(
                    isUpdating = false,
                    updateMessage = "No se pudo actualizar: ${err.message ?: "error de red"}",
                )
                false
            },
        )
    }

    /**
     * Descarga solo el audio de una URL compatible (YouTube, etc.) usando yt-dlp + yt-dlp-ejs +
     * QuickJS-NG (`libaura_qjs.so`) e importa automáticamente la pista a la biblioteca local.
     */
    suspend fun downloadAudio(rawUrl: String, accentArgb: Int) = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeAndValidateUrl(rawUrl)
        if (cleanUrl == null) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = rawUrl,
                errorMessage = "Ingresa una URL válida que empiece por http:// o https://",
            )
            return@withContext
        }

        if (_downloadState.value.isBusy) return@withContext

        if (preferencesRepository.autoUpdateEnabled.first()) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.CHECKING_UPDATES,
                sourceUrl = cleanUrl,
                statusMessage = "Verificando actualizaciones de yt-dlp y yt-dlp-ejs...",
            )
            checkAndUpdateEngines(force = false)
        }

        _downloadState.value = DownloadState(
            status = DownloadStatus.RESOLVING,
            sourceUrl = cleanUrl,
            statusMessage = "Resolviendo audio y reto EJS con QuickJS-NG...",
        )

        val qjsPath = AuraRustBridge.resolveQuickJsBinaryPath(context)

        val downloadResult = runCatching {
            pythonMutex.withLock {
                val py = ensurePythonStarted()
                val mod = py.getModule("aura_ytdlp")
                val callback = PyProgressBridge { payloadJson ->
                    onPythonProgress(payloadJson)
                }
                val resultJson = mod.callAttr(
                    "download_audio",
                    cleanUrl,
                    downloadsDir.absolutePath,
                    updatesDir.absolutePath,
                    qjsPath,
                    callback,
                ).toString()
                JSONObject(resultJson)
            }
        }

        val info = downloadResult.getOrElse { err ->
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = cleanUrl,
                errorMessage = humanizeDownloadError(err.message),
            )
            return@withContext
        }

        val audioFile = File(info.optString("audio_path"))
        val thumbPath = info.optString("thumbnail_path").takeIf { it.isNotBlank() }
        val thumbFile = thumbPath?.let(::File)?.takeIf { it.exists() }
        val title = info.optString("title", "Audio descargado")
        val artist = info.optString("artist", "YouTube")
        val album = info.optString("album", "Descargas Aura")
        val durationMs = info.optLong("duration_ms", 0L)

        _downloadState.value = _downloadState.value.copy(
            status = DownloadStatus.IMPORTING,
            title = title,
            progressPercent = 100f,
            statusMessage = "Leyendo metadatos con Rust (lofty) y agregando a tu biblioteca...",
        )

        val importedTrack = audioImporter.importDownloadedAudioFile(
            audioFile = audioFile,
            fallbackTitle = title,
            fallbackArtist = artist,
            fallbackAlbum = album,
            fallbackDurationMs = durationMs,
            thumbnailFile = thumbFile,
            accentArgb = accentArgb,
        )

        _downloadState.value = if (importedTrack != null) {
            DownloadState(
                status = DownloadStatus.COMPLETED,
                sourceUrl = cleanUrl,
                title = importedTrack.title,
                progressPercent = 100f,
                statusMessage = "Agregada a tu biblioteca: ${importedTrack.title}",
                lastCompletedTrack = importedTrack,
            )
        } else {
            DownloadState(
                status = DownloadStatus.COMPLETED,
                sourceUrl = cleanUrl,
                title = title,
                progressPercent = 100f,
                statusMessage = "La pista ya existía o se completó en tu biblioteca.",
            )
        }
    }

    fun clearDownloadStatus() {
        if (!_downloadState.value.isBusy) {
            _downloadState.value = DownloadState()
        }
    }

    fun clearUpdateMessage() {
        _engineStatus.value = _engineStatus.value.copy(updateMessage = null)
    }

    private fun ensurePythonStarted(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    private fun onPythonProgress(payloadJson: String) {
        val json = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        val stage = json.optString("stage")
        val percent = json.optDouble("percent", 0.0).toFloat()
        val downloaded = json.optLong("downloaded", 0L)
        val total = json.optLong("total", 0L)
        val title = json.optString("title").takeIf { it.isNotBlank() }

        val mappedStatus = when (stage) {
            "resolving" -> DownloadStatus.RESOLVING
            "downloading" -> DownloadStatus.DOWNLOADING
            "importing" -> DownloadStatus.IMPORTING
            else -> _downloadState.value.status
        }

        val message = when (mappedStatus) {
            DownloadStatus.RESOLVING -> "Resolviendo enlace de audio con QuickJS-NG..."
            DownloadStatus.DOWNLOADING -> "Descargando audio (${percent.toInt().coerceIn(0, 100)}%)..."
            DownloadStatus.IMPORTING -> "Finalizando descarga..."
            else -> _downloadState.value.statusMessage
        }

        _downloadState.value = _downloadState.value.copy(
            status = mappedStatus,
            title = title ?: _downloadState.value.title,
            progressPercent = percent,
            downloadedBytes = downloaded,
            totalBytes = total,
            statusMessage = message,
        )
    }

    /**
     * Puente invocable desde Python (`progress_callback(json_str)`) mediante Chaquopy.
     */
    class PyProgressBridge(
        private val onProgress: (String) -> Unit,
    ) {
        @Suppress("unused")
        fun __call__(payloadJson: String) {
            onProgress(payloadJson)
        }
    }

    companion object {
        /**
         * Valida y normaliza una URL HTTP/HTTPS antes de enviarla a yt-dlp.
         */
        fun normalizeAndValidateUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val lower = trimmed.lowercase()
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return null
            }
            if (trimmed.any { it.isWhitespace() }) return null
            return trimmed
        }

        fun humanizeDownloadError(rawMessage: String?): String {
            val msg = rawMessage.orEmpty()
            return when {
                msg.contains("Sign in to confirm", ignoreCase = true) ||
                    msg.contains("not a bot", ignoreCase = true) ->
                    "YouTube bloqueó temporalmente esta IP pidiendo verificación antibot. Intenta actualizar yt-dlp/yt-dlp-ejs o cambiar de red."
                msg.contains("HTTP Error 403", ignoreCase = true) ->
                    "YouTube devolvió error 403. Prueba pulsar 'Actualizar ahora' para bajar el parche más reciente de yt-dlp y yt-dlp-ejs."
                msg.contains("Unsupported URL", ignoreCase = true) ->
                    "El enlace ingresado no es compatible o está incompleto."
                msg.isBlank() ->
                    "No se pudo completar la descarga de audio."
                else -> msg
            }
        }
    }
}
