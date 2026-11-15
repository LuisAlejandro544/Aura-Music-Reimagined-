package com.auramusic.core.downloader

import android.content.Context
import com.auramusic.core.database.DownloaderPreferencesRepository
import com.auramusic.core.importer.AudioImporter
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.DownloadAudioFormatOption
import com.auramusic.core.model.DownloadFormatHelper
import com.auramusic.core.model.DownloadPreview
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.rust.AuraRustBridge
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinador Kotlin del motor de descargas de audio de Aura Music.
 *
 * En modo trabajador aislado (`remoteWorkerMode = true` dentro de `:downloader`),
 * ejecuta Chaquopy + `yt-dlp` + `yt-dlp-ejs` + `libaura_qjs.so` sin abrir nunca
 * Room ni DataStore en paralelo, dejando las pistas listas en `filesDir/pending_imports/`
 * para que el proceso principal (`MAIN`) las indexe de forma única y atómica.
 */
class YtDlpAudioDownloader(
    private val context: Context,
    private val audioImporter: AudioImporter? = null,
    private val preferencesRepository: DownloaderPreferencesRepository? = null,
    private val remoteWorkerMode: Boolean = audioImporter == null || preferencesRepository == null,
    private val onPendingImportCreated: ((String) -> Unit)? = null,
    private val onUpdateCheckTimestamp: ((Long) -> Unit)? = null,
) {
    private val _downloadState = MutableStateFlow(DownloadState())
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _engineStatus = MutableStateFlow(DownloaderEngineStatus())
    val engineStatus: StateFlow<DownloaderEngineStatus> = _engineStatus.asStateFlow()

    private val pythonMutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var cachedAutoUpdateEnabled: Boolean = true

    @Volatile
    private var cachedPreferredQuality: AudioDownloadQuality = AudioDownloadQuality.MAX

    @Volatile
    private var cachedLastUpdateCheckEpochMs: Long = 0L

    private val updatesDir: File
        get() = File(context.filesDir, "py_updates").apply { mkdirs() }

    private val downloadsDir: File
        get() = File(context.filesDir, "downloads").apply { mkdirs() }

    private val previewCacheDir: File
        get() = File(context.cacheDir, "preview_thumbs").apply { mkdirs() }

    /**
     * Actualiza en memoria los parámetros recibidos por IPC cuando el trabajador corre
     * en el proceso aislado `:downloader` (sin acceso directo a DataStore).
     */
    fun applyRemotePreferences(
        autoUpdateEnabled: Boolean? = null,
        preferredQuality: AudioDownloadQuality? = null,
        lastUpdateCheckEpochMs: Long? = null,
    ) {
        autoUpdateEnabled?.let { cachedAutoUpdateEnabled = it }
        preferredQuality?.let { cachedPreferredQuality = it }
        lastUpdateCheckEpochMs?.let { cachedLastUpdateCheckEpochMs = it }
        _engineStatus.value = _engineStatus.value.copy(
            autoUpdateEnabled = cachedAutoUpdateEnabled,
            preferredQuality = cachedPreferredQuality,
        )
    }

    /**
     * Restaura una vista previa enviada por IPC si el servicio `:downloader` se reinició
     * justo antes de pulsar "Confirmar descarga".
     */
    fun restorePreviewIfNeeded(preview: DownloadPreview?) {
        if (preview == null) return
        val current = _downloadState.value
        if (current.preview == null && !current.isBusy) {
            _downloadState.value = current.copy(
                status = DownloadStatus.PREVIEW_READY,
                sourceUrl = preview.sourceUrl,
                title = preview.title,
                totalBytes = preview.estimatedSizeBytes,
                preview = preview,
            )
        }
    }

    /**
     * Marca en el estado del motor que una actualización manual ya está en cola para ejecutarse
     * inmediatamente después de cualquier lectura pendiente, evitando parpadeos del indicador.
     */
    fun markUpdateQueued() {
        _engineStatus.value = _engineStatus.value.copy(
            isUpdating = true,
            updateMessage = "Buscando actualizaciones de yt-dlp y yt-dlp-ejs...",
        )
    }

    /**
     * Inicializa Chaquopy (si aún no está iniciado) y lee las versiones activas
     * de Python, yt-dlp, yt-dlp-ejs y QuickJS-NG fuera del hilo principal.
     */
    suspend fun refreshEngineStatus(
        autoUpdateOverride: Boolean? = null,
        qualityOverride: AudioDownloadQuality? = null,
    ) = withContext(Dispatchers.IO) {
        val autoUpdate = autoUpdateOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.autoUpdateEnabled.first()
            } else {
                cachedAutoUpdateEnabled
            }
        val quality = qualityOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.audioDownloadQuality.first()
            } else {
                cachedPreferredQuality
            }
        cachedAutoUpdateEnabled = autoUpdate
        cachedPreferredQuality = quality

        val quickJsVer = withContext(Dispatchers.Default) {
            AuraRustBridge.getQuickJsVersion()
        }

        val pyResult = runCatching {
            pythonMutex.withLock {
                val py = ensurePythonStarted()
                val mod = py.getModule("aura_ytdlp")
                val rawJson = mod.callAttr("get_engine_versions", updatesDir.absolutePath).toString()
                JSONObject(rawJson)
            }
        }

        pyResult.fold(
            onSuccess = { pyInfo ->
                val pythonVer = pyInfo.optString("python_version").takeIf { it.isNotBlank() }
                    ?: "Python 3.11 (ligero)"
                val ytDlpVer = pyInfo.optString("yt_dlp_version").takeIf { it.isNotBlank() }
                    ?: "No inicializado"
                val ytDlpEjsVer = pyInfo.optString("yt_dlp_ejs_version").takeIf { it.isNotBlank() }
                    ?: "No inicializado"
                val ytDlpUpdated = pyInfo.optString("yt_dlp_updated_at").takeIf { it.isNotBlank() }
                val ytDlpEjsUpdated = pyInfo.optString("yt_dlp_ejs_updated_at").takeIf { it.isNotBlank() }

                AuraDownloaderIpc.writeCachedEngineVersions(
                    context = context,
                    pythonVersion = pythonVer,
                    ytDlpVersion = ytDlpVer,
                    ytDlpEjsVersion = ytDlpEjsVer,
                    ytDlpUpdatedAt = ytDlpUpdated,
                    ytDlpEjsUpdatedAt = ytDlpEjsUpdated,
                )

                _engineStatus.value = _engineStatus.value.copy(
                    pythonVersion = pythonVer,
                    ytDlpVersion = ytDlpVer,
                    ytDlpEjsVersion = ytDlpEjsVer,
                    quickJsVersion = quickJsVer,
                    ytDlpUpdatedAt = ytDlpUpdated,
                    ytDlpEjsUpdatedAt = ytDlpEjsUpdated,
                    autoUpdateEnabled = autoUpdate,
                    preferredQuality = quality,
                )
            },
            onFailure = { err ->
                val detail = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                _engineStatus.value = _engineStatus.value.copy(
                    pythonVersion = "Python 3.11 (ligero)",
                    ytDlpVersion = "No inicializado",
                    ytDlpEjsVersion = "No inicializado",
                    quickJsVersion = quickJsVer,
                    autoUpdateEnabled = autoUpdate,
                    preferredQuality = quality,
                    isUpdating = false,
                    updateMessage = "No se pudo inicializar el motor Python/yt-dlp: $detail",
                )
            },
        )
    }

    /**
     * Cambia la calidad de audio preferida para nuevas vistas previas y descargas.
     * Si hay una vista previa abierta, ajusta automáticamente el formato preseleccionado.
     */
    suspend fun setPreferredAudioQuality(quality: AudioDownloadQuality) {
        cachedPreferredQuality = quality
        if (!remoteWorkerMode) {
            preferencesRepository?.setAudioDownloadQuality(quality)
        }
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
    }

    /**
     * Selecciona manualmente un formato/bitrate específico dentro de la vista previa actual.
     */
    fun selectPreviewAudioFormat(formatId: String) {
        val current = _downloadState.value
        val preview = current.preview ?: return
        if (current.status != DownloadStatus.PREVIEW_READY) return
        val updatedPreview = DownloadFormatHelper.selectOption(preview, formatId)
        _downloadState.value = current.copy(
            preview = updatedPreview,
            totalBytes = updatedPreview.estimatedSizeBytes,
        )
    }

    /**
     * Actualiza `yt-dlp` y `yt-dlp-ejs` independientemente desde PyPI sin recompilar el APK.
     */
    suspend fun checkAndUpdateEngines(
        force: Boolean = true,
        autoUpdateOverride: Boolean? = null,
        lastCheckOverride: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val autoEnabled = autoUpdateOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.autoUpdateEnabled.first()
            } else {
                cachedAutoUpdateEnabled
            }
        val lastCheck = lastCheckOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.lastUpdateCheckEpochMs.first()
            } else {
                cachedLastUpdateCheckEpochMs
            }

        if (!force) {
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

        cachedLastUpdateCheckEpochMs = now
        if (!remoteWorkerMode) {
            preferencesRepository?.setLastUpdateCheckEpochMs(now)
        }
        onUpdateCheckTimestamp?.invoke(now)

        val quickJsVer = withContext(Dispatchers.Default) {
            AuraRustBridge.getQuickJsVersion()
        }

        return@withContext result.fold(
            onSuccess = { json ->
                val ok = json.optBoolean("ok", true)
                val pythonVer = json.optString("python_version", _engineStatus.value.pythonVersion)
                val ytDlpVer = json.optString("yt_dlp_version", _engineStatus.value.ytDlpVersion)
                val ytDlpEjsVer = json.optString("yt_dlp_ejs_version", _engineStatus.value.ytDlpEjsVersion)
                val ytDlpUpdated = json.optString("yt_dlp_updated_at").takeIf { it.isNotBlank() }
                val ytDlpEjsUpdated = json.optString("yt_dlp_ejs_updated_at").takeIf { it.isNotBlank() }

                AuraDownloaderIpc.writeCachedEngineVersions(
                    context = context,
                    pythonVersion = pythonVer,
                    ytDlpVersion = ytDlpVer,
                    ytDlpEjsVersion = ytDlpEjsVer,
                    ytDlpUpdatedAt = ytDlpUpdated,
                    ytDlpEjsUpdatedAt = ytDlpEjsUpdated,
                )

                _engineStatus.value = _engineStatus.value.copy(
                    pythonVersion = pythonVer,
                    ytDlpVersion = ytDlpVer,
                    ytDlpEjsVersion = ytDlpEjsVer,
                    quickJsVersion = quickJsVer,
                    ytDlpUpdatedAt = ytDlpUpdated,
                    ytDlpEjsUpdatedAt = ytDlpEjsUpdated,
                    isUpdating = false,
                    updateMessage = json.optString("message").takeIf { it.isNotBlank() },
                )
                ok
            },
            onFailure = { err ->
                val detail = err.message?.takeIf { it.isNotBlank() } ?: err.javaClass.simpleName
                _engineStatus.value = _engineStatus.value.copy(
                    quickJsVersion = quickJsVer,
                    isUpdating = false,
                    updateMessage = "No se pudo actualizar: $detail",
                )
                false
            },
        )
    }

    /**
     * Analiza un enlace sin descargar el audio aún para mostrar título, miniatura,
     * canal/artista, duración y peso estimado antes de que el usuario confirme.
     */
    suspend fun inspectAudioUrl(
        rawUrl: String,
        autoUpdateOverride: Boolean? = null,
        qualityOverride: AudioDownloadQuality? = null,
        lastCheckOverride: Long? = null,
    ) = withContext(Dispatchers.IO) {
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

        cleanupPreviewThumbs()

        val autoUpdate = autoUpdateOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.autoUpdateEnabled.first()
            } else {
                cachedAutoUpdateEnabled
            }
        val preferredQuality = qualityOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.audioDownloadQuality.first()
            } else {
                cachedPreferredQuality
            }
        cachedAutoUpdateEnabled = autoUpdate
        cachedPreferredQuality = preferredQuality

        if (autoUpdate) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.CHECKING_UPDATES,
                sourceUrl = cleanUrl,
                statusMessage = "Verificando actualizaciones de yt-dlp y yt-dlp-ejs...",
            )
            checkAndUpdateEngines(
                force = false,
                autoUpdateOverride = autoUpdate,
                lastCheckOverride = lastCheckOverride,
            )
        }

        _downloadState.value = DownloadState(
            status = DownloadStatus.FETCHING_INFO,
            sourceUrl = cleanUrl,
            statusMessage = "Obteniendo información del enlace con QuickJS-NG...",
        )

        _engineStatus.value = _engineStatus.value.copy(
            autoUpdateEnabled = autoUpdate,
            preferredQuality = preferredQuality,
        )
        val qjsPath = AuraRustBridge.resolveQuickJsBinaryPath(context)

        val inspectResult = runCatching {
            pythonMutex.withLock {
                val py = ensurePythonStarted()
                val mod = py.getModule("aura_ytdlp")
                val rawJson = mod.callAttr(
                    "inspect_audio_preview",
                    cleanUrl,
                    previewCacheDir.absolutePath,
                    updatesDir.absolutePath,
                    qjsPath,
                    preferredQuality.key,
                ).toString()
                JSONObject(rawJson)
            }
        }

        val info = inspectResult.getOrElse { err ->
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = cleanUrl,
                errorMessage = humanizeDownloadError(err.message),
            )
            return@withContext
        }

        val availableFormats = parseAvailableFormats(info)
        val rawSelectedId = info.optString("selected_format_id").takeIf { it.isNotBlank() }
        val defaultOption = DownloadFormatHelper.pickDefaultOption(availableFormats, preferredQuality)
        val initialSelectedId = availableFormats.firstOrNull { it.formatId == rawSelectedId }?.formatId
            ?: defaultOption?.formatId

        val basePreview = DownloadPreview(
            sourceUrl = info.optString("webpage_url").takeIf { it.isNotBlank() } ?: cleanUrl,
            videoId = info.optString("video_id"),
            title = info.optString("title", "Audio sin título"),
            channelOrArtist = info.optString("artist", "YouTube"),
            durationMs = info.optLong("duration_ms", 0L),
            estimatedSizeBytes = info.optLong("estimated_size_bytes", 0L),
            audioFormatLabel = info.optString("audio_format_label"),
            localThumbnailPath = info.optString("thumbnail_path").takeIf { it.isNotBlank() },
            availableFormats = availableFormats,
            selectedFormatId = initialSelectedId,
        )
        val preview = if (initialSelectedId != null) {
            DownloadFormatHelper.selectOption(basePreview, initialSelectedId)
        } else {
            basePreview
        }

        _downloadState.value = DownloadState(
            status = DownloadStatus.PREVIEW_READY,
            sourceUrl = cleanUrl,
            title = preview.title,
            totalBytes = preview.estimatedSizeBytes,
            preview = preview,
            statusMessage = "Confirma los datos antes de iniciar la descarga.",
        )
    }

    private fun parseAvailableFormats(info: JSONObject): List<DownloadAudioFormatOption> {
        val array = info.optJSONArray("available_formats") ?: return emptyList()
        return buildList(array.length()) {
            for (idx in 0 until array.length()) {
                val item = array.optJSONObject(idx) ?: continue
                val formatId = item.optString("format_id").trim()
                if (formatId.isEmpty()) continue
                add(
                    DownloadAudioFormatOption(
                        formatId = formatId,
                        extension = item.optString("extension", "audio"),
                        codec = item.optString("codec", ""),
                        bitrateKbps = item.optInt("bitrate_kbps", 0),
                        estimatedSizeBytes = item.optLong("estimated_size_bytes", 0L),
                        label = item.optString("label", ""),
                    ),
                )
            }
        }
    }

    /**
     * Inicia la descarga confirmada dentro del servicio en primer plano ([AuraDownloadForegroundService]),
     * ejecutando todo el runtime Python dentro del proceso aislado `:downloader`.
     */
    fun startBackgroundConfirmedDownload(accentArgb: Int) {
        val current = _downloadState.value
        if (current.preview == null || current.isBusy) return
        cancelRequested.set(false)
        AuraDownloadForegroundService.startConfirmedDownload(
            context = context,
            accentArgb = accentArgb,
            preview = current.preview,
            preferredQuality = cachedPreferredQuality,
            autoUpdateEnabled = cachedAutoUpdateEnabled,
            lastCheckEpochMs = cachedLastUpdateCheckEpochMs,
        )
    }

    /**
     * Inicia una descarga directa por URL dentro del servicio en primer plano ([AuraDownloadForegroundService]).
     */
    fun startBackgroundUrlDownload(rawUrl: String, accentArgb: Int) {
        val cleanUrl = normalizeAndValidateUrl(rawUrl)
        if (cleanUrl == null) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.ERROR,
                sourceUrl = rawUrl,
                errorMessage = "Ingresa una URL válida que empiece por http:// o https://",
            )
            return
        }
        if (_downloadState.value.isBusy) return
        cancelRequested.set(false)
        AuraDownloadForegroundService.startUrlDownload(
            context = context,
            rawUrl = cleanUrl,
            accentArgb = accentArgb,
            preferredQuality = cachedPreferredQuality,
            autoUpdateEnabled = cachedAutoUpdateEnabled,
            lastCheckEpochMs = cachedLastUpdateCheckEpochMs,
        )
    }

    /**
     * Solicita cancelar la descarga en curso de forma segura tanto desde la app como desde la notificación.
     */
    fun requestCancelDownload() {
        if (!_downloadState.value.isBusy) return
        cancelRequested.set(true)
        _downloadState.value = _downloadState.value.copy(
            statusMessage = "Cancelando descarga en curso...",
        )
    }

    /**
     * Inicia la descarga del audio previamente previsualizado y confirmado por el usuario.
     */
    suspend fun confirmPendingDownload(
        accentArgb: Int,
        autoUpdateOverride: Boolean? = null,
        qualityOverride: AudioDownloadQuality? = null,
        lastCheckOverride: Long? = null,
    ) {
        val current = _downloadState.value
        val preview = current.preview ?: return
        downloadAudio(
            rawUrl = preview.sourceUrl.ifBlank { current.sourceUrl },
            accentArgb = accentArgb,
            preview = preview,
            autoUpdateOverride = autoUpdateOverride,
            qualityOverride = qualityOverride,
            lastCheckOverride = lastCheckOverride,
        )
    }

    /**
     * Descarga solo el flujo de audio de una URL compatible usando yt-dlp + yt-dlp-ejs +
     * QuickJS-NG (`libaura_qjs.so`). En modo trabajador remoto, deja un descriptor pendiente
     * en `filesDir/pending_imports/` para que `MAIN` lo indexe en Room de forma exclusiva.
     */
    suspend fun downloadAudio(
        rawUrl: String,
        accentArgb: Int,
        preview: DownloadPreview? = null,
        autoUpdateOverride: Boolean? = null,
        qualityOverride: AudioDownloadQuality? = null,
        lastCheckOverride: Long? = null,
    ) = withContext(Dispatchers.IO) {
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
        cancelRequested.set(false)

        val autoUpdate = autoUpdateOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.autoUpdateEnabled.first()
            } else {
                cachedAutoUpdateEnabled
            }
        val preferredQuality = qualityOverride
            ?: if (!remoteWorkerMode && preferencesRepository != null) {
                preferencesRepository.audioDownloadQuality.first()
            } else {
                cachedPreferredQuality
            }
        cachedAutoUpdateEnabled = autoUpdate
        cachedPreferredQuality = preferredQuality

        if (preview == null && autoUpdate) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.CHECKING_UPDATES,
                sourceUrl = cleanUrl,
                statusMessage = "Verificando actualizaciones de yt-dlp y yt-dlp-ejs...",
            )
            checkAndUpdateEngines(
                force = false,
                autoUpdateOverride = autoUpdate,
                lastCheckOverride = lastCheckOverride,
            )
        }

        if (cancelRequested.get()) {
            _downloadState.value = DownloadState(
                status = DownloadStatus.CANCELLED,
                sourceUrl = cleanUrl,
                title = preview?.title,
                statusMessage = "Descarga cancelada por el usuario.",
            )
            return@withContext
        }

        _downloadState.value = DownloadState(
            status = DownloadStatus.RESOLVING,
            sourceUrl = cleanUrl,
            title = preview?.title,
            totalBytes = preview?.estimatedSizeBytes ?: 0L,
            preview = preview,
            statusMessage = "Resolviendo flujo de audio con QuickJS-NG...",
        )

        val qjsPath = AuraRustBridge.resolveQuickJsBinaryPath(context)

        val downloadResult = runCatching {
            pythonMutex.withLock {
                val py = ensurePythonStarted()
                val mod = py.getModule("aura_ytdlp")
                val callback = PyProgressBridge { payloadJson ->
                    onPythonProgress(payloadJson, preview?.estimatedSizeBytes ?: 0L)
                }
                val cancelBridge = PyCancelBridge { cancelRequested.get() }
                val resultJson = mod.callAttr(
                    "download_audio",
                    cleanUrl,
                    downloadsDir.absolutePath,
                    updatesDir.absolutePath,
                    qjsPath,
                    callback,
                    cancelBridge,
                    preferredQuality.key,
                    preview?.selectedFormatId,
                ).toString()
                JSONObject(resultJson)
            }
        }

        val info = downloadResult.getOrElse { err ->
            val rawMsg = err.message.orEmpty()
            val wasCancelled = cancelRequested.get() ||
                rawMsg.contains("DownloadCancelledError", ignoreCase = true) ||
                rawMsg.contains("cancelada por el usuario", ignoreCase = true)

            if (wasCancelled) {
                cleanupPartialDownloadFiles()
                _downloadState.value = DownloadState(
                    status = DownloadStatus.CANCELLED,
                    sourceUrl = cleanUrl,
                    title = preview?.title,
                    statusMessage = "Descarga cancelada por el usuario.",
                )
            } else {
                _downloadState.value = DownloadState(
                    status = DownloadStatus.ERROR,
                    sourceUrl = cleanUrl,
                    title = preview?.title,
                    errorMessage = humanizeDownloadError(err.message),
                )
            }
            return@withContext
        }

        val audioFile = File(info.optString("audio_path"))
        val thumbPath = info.optString("thumbnail_path").takeIf { it.isNotBlank() }
        val thumbFile = thumbPath?.let(::File)?.takeIf { it.exists() }
            ?: preview?.localThumbnailPath?.let(::File)?.takeIf { it.exists() }
        val title = info.optString("title", preview?.title ?: "Audio descargado")
        val artist = info.optString("artist", preview?.channelOrArtist ?: "YouTube")
        val album = info.optString("album", "Descargas Aura")
        val durationMs = info.optLong("duration_ms", preview?.durationMs ?: 0L)

        _downloadState.value = _downloadState.value.copy(
            status = DownloadStatus.IMPORTING,
            title = title,
            progressPercent = 100f,
            speedBytesPerSec = 0L,
            statusMessage = "Leyendo metadatos con Rust (lofty) y agregando a tu biblioteca...",
        )

        if (remoteWorkerMode || audioImporter == null) {
            val pendingDir = AuraDownloaderIpc.pendingImportsDir(context)
            val seq = pendingSeq.incrementAndGet()
            val stamp = System.currentTimeMillis()
            // Preserva la miniatura fuera de preview_thumbs antes de limpiar la caché temporal.
            val stagedThumbPath = if (thumbFile != null && thumbFile.exists()) {
                val stagedFile = File(pendingDir, "thumb_${stamp}_$seq.img")
                runCatching {
                    thumbFile.copyTo(stagedFile, overwrite = true)
                    stagedFile.absolutePath
                }.getOrNull()
            } else {
                null
            }

            val descriptor = PendingDownloadedImport(
                sourceUrl = cleanUrl,
                audioPath = audioFile.absolutePath,
                thumbnailPath = stagedThumbPath,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                accentArgb = accentArgb,
            )
            val descriptorFile = File(pendingDir, "dl_${stamp}_$seq.json")
            val tmpFile = File(pendingDir, "dl_${stamp}_$seq.json.tmp")
            tmpFile.writeText(descriptor.toJson())
            if (!tmpFile.renameTo(descriptorFile)) {
                descriptorFile.writeText(descriptor.toJson())
                tmpFile.delete()
            }

            cleanupPreviewThumbs()
            onPendingImportCreated?.invoke(descriptorFile.absolutePath)

            _downloadState.value = DownloadState(
                status = DownloadStatus.COMPLETED,
                sourceUrl = cleanUrl,
                title = title,
                progressPercent = 100f,
                statusMessage = "Agregada a tu biblioteca: $title",
            )
            return@withContext
        }

        val importedTrack = audioImporter.importDownloadedAudioFile(
            audioFile = audioFile,
            fallbackTitle = title,
            fallbackArtist = artist,
            fallbackAlbum = album,
            fallbackDurationMs = durationMs,
            thumbnailFile = thumbFile,
            accentArgb = accentArgb,
        )

        cleanupPreviewThumbs()

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
            cancelRequested.set(false)
            cleanupPreviewThumbs()
            _downloadState.value = DownloadState()
        }
    }

    fun clearUpdateMessage() {
        _engineStatus.value = _engineStatus.value.copy(updateMessage = null)
    }

    private fun cleanupPreviewThumbs() {
        runCatching {
            previewCacheDir.listFiles()?.forEach { file ->
                if (file.isFile) file.delete()
            }
        }
    }

    private fun cleanupPartialDownloadFiles() {
        runCatching {
            downloadsDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".part") || file.name.endsWith(".ytdl"))) {
                    file.delete()
                }
            }
        }
    }

    private fun ensurePythonStarted(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
    }

    private fun onPythonProgress(payloadJson: String, fallbackTotalBytes: Long) {
        if (cancelRequested.get()) return
        val json = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return
        val stage = json.optString("stage")
        val percent = json.optDouble("percent", 0.0).toFloat()
        val downloaded = json.optLong("downloaded", 0L)
        val reportedTotal = json.optLong("total", 0L)
        val total = if (reportedTotal > 0L) reportedTotal else fallbackTotalBytes
        val speedBps = json.optLong("speed_bps", 0L).coerceAtLeast(0L)
        val title = json.optString("title").takeIf { it.isNotBlank() }

        val mappedStatus = when (stage) {
            "resolving" -> DownloadStatus.RESOLVING
            "downloading" -> DownloadStatus.DOWNLOADING
            "importing" -> DownloadStatus.IMPORTING
            else -> _downloadState.value.status
        }

        val effectivePercent = if (percent > 0f) {
            percent
        } else if (total > 0L && downloaded > 0L) {
            (downloaded.toFloat() * 100f / total.toFloat()).coerceIn(0f, 100f)
        } else {
            0f
        }

        val message = when (mappedStatus) {
            DownloadStatus.RESOLVING -> "Resolviendo enlace de audio con QuickJS-NG..."
            DownloadStatus.DOWNLOADING -> "Descargando audio (${effectivePercent.toInt().coerceIn(0, 100)}%)..."
            DownloadStatus.IMPORTING -> "Finalizando descarga..."
            else -> _downloadState.value.statusMessage
        }

        _downloadState.value = _downloadState.value.copy(
            status = mappedStatus,
            title = title ?: _downloadState.value.title,
            progressPercent = effectivePercent,
            downloadedBytes = downloaded,
            totalBytes = total,
            speedBytesPerSec = speedBps,
            statusMessage = message,
        )
    }

    /**
     * Puente invocable desde Python (`progress_callback(json_str)`) mediante Chaquopy.
     */
    class PyProgressBridge(
        private val onProgress: (String) -> Unit,
    ) {
        @Suppress("unused", "ktlint:standard:function-naming")
        fun __call__(payloadJson: String) {
            onProgress(payloadJson)
        }
    }

    /**
     * Puente invocable desde Python (`cancel_check()`) para abortar la descarga al instante.
     */
    class PyCancelBridge(
        private val isCancelled: () -> Boolean,
    ) {
        @Suppress("unused", "ktlint:standard:function-naming")
        fun __call__(): Boolean = isCancelled()
    }

    companion object {
        private val pendingSeq = AtomicLong(0L)

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
                    "YouTube bloqueó temporalmente esta IP pidiendo verificación antibot. " +
                        "Intenta actualizar yt-dlp/yt-dlp-ejs o cambiar de red."
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
