package com.auramusic.core.downloader

import android.content.Context
import com.auramusic.core.model.AudioDownloadQuality
import com.auramusic.core.model.DownloadAudioFormatOption
import com.auramusic.core.model.DownloadPreview
import com.auramusic.core.model.DownloadState
import com.auramusic.core.model.DownloadStatus
import com.auramusic.core.model.DownloaderEngineStatus
import com.auramusic.core.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Contrato IPC entre el proceso principal (`MAIN`) y el proceso aislado `:downloader`
 * (`AuraDownloadForegroundService` + Chaquopy/Python + `yt-dlp`).
 *
 * Permite mantener todo el runtime de Python 3.11 fuera de la memoria del reproductor
 * y de la interfaz principal, intercambiando únicamente estados JSON ligeros y archivos
 * de importación pendiente en `filesDir/pending_imports/`.
 */
object AuraDownloaderIpc {
    const val ACTION_PENDING_IMPORT_READY = "com.auramusic.downloader.ACTION_PENDING_IMPORT_READY"

    // Comandos cliente -> servicio (:downloader)
    const val MSG_REGISTER_CLIENT = 1
    const val MSG_UNREGISTER_CLIENT = 2
    const val MSG_REFRESH_STATUS = 3
    const val MSG_CHECK_UPDATES = 4
    const val MSG_INSPECT_URL = 5
    const val MSG_SELECT_FORMAT = 6
    const val MSG_SET_QUALITY = 7
    const val MSG_CLEAR_STATUS = 8
    const val MSG_CLEAR_UPDATE_MESSAGE = 9
    const val MSG_CANCEL_DOWNLOAD = 10
    const val MSG_SET_AUTO_UPDATE = 11

    // Eventos servicio (:downloader) -> cliente (MAIN)
    const val MSG_DOWNLOAD_STATE = 101
    const val MSG_ENGINE_STATUS = 102
    const val MSG_UPDATE_CHECK_TIMESTAMP = 103
    const val MSG_PENDING_IMPORT_SIGNAL = 104

    const val KEY_JSON = "json"
    const val KEY_URL = "url"
    const val KEY_FORMAT_ID = "format_id"
    const val KEY_QUALITY_KEY = "quality_key"
    const val KEY_AUTO_UPDATE = "auto_update"
    const val KEY_LAST_CHECK_MS = "last_check_ms"
    const val KEY_FORCE = "force"
    const val KEY_EPOCH_MS = "epoch_ms"
    const val KEY_FILE_PATH = "file_path"

    private const val ENGINE_VERSIONS_FILE = "engine_versions.json"
    private const val PENDING_IMPORTS_DIR = "pending_imports"

    fun pendingImportsDir(context: Context): File =
        File(context.filesDir, PENDING_IMPORTS_DIR).apply { mkdirs() }

    fun engineVersionsFile(context: Context): File =
        File(File(context.filesDir, "py_updates").apply { mkdirs() }, ENGINE_VERSIONS_FILE)

    fun writeCachedEngineVersions(
        context: Context,
        pythonVersion: String,
        ytDlpVersion: String,
        ytDlpEjsVersion: String,
        ytDlpUpdatedAt: String?,
        ytDlpEjsUpdatedAt: String?,
    ) {
        runCatching {
            val json = JSONObject().apply {
                put("python_version", pythonVersion)
                put("yt_dlp_version", ytDlpVersion)
                put("yt_dlp_ejs_version", ytDlpEjsVersion)
                put("yt_dlp_updated_at", ytDlpUpdatedAt.orEmpty())
                put("yt_dlp_ejs_updated_at", ytDlpEjsUpdatedAt.orEmpty())
            }
            val target = engineVersionsFile(context)
            val tmp = File(target.parentFile, "$ENGINE_VERSIONS_FILE.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                tmp.delete()
            }
        }
    }

    fun readCachedEngineVersions(context: Context): CachedEngineVersions? {
        return runCatching {
            val file = engineVersionsFile(context)
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            CachedEngineVersions(
                pythonVersion = json.optString("python_version").takeIf { it.isNotBlank() }
                    ?: "Python 3.11 (ligero)",
                ytDlpVersion = json.optString("yt_dlp_version").takeIf { it.isNotBlank() }
                    ?: "No inicializado",
                ytDlpEjsVersion = json.optString("yt_dlp_ejs_version").takeIf { it.isNotBlank() }
                    ?: "No inicializado",
                ytDlpUpdatedAt = json.optString("yt_dlp_updated_at").takeIf { it.isNotBlank() },
                ytDlpEjsUpdatedAt = json.optString("yt_dlp_ejs_updated_at").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    fun downloadStateToJson(state: DownloadState): String {
        val obj = JSONObject().apply {
            put("status", state.status.name)
            put("sourceUrl", state.sourceUrl)
            put("title", state.title ?: "")
            put("progressPercent", state.progressPercent.toDouble())
            put("downloadedBytes", state.downloadedBytes)
            put("totalBytes", state.totalBytes)
            put("speedBytesPerSec", state.speedBytesPerSec)
            put("statusMessage", state.statusMessage ?: "")
            put("errorMessage", state.errorMessage ?: "")
            state.preview?.let { put("preview", previewToJsonObject(it)) }
            state.lastCompletedTrack?.let { put("lastCompletedTrack", trackToJsonObject(it)) }
        }
        return obj.toString()
    }

    fun downloadStateFromJson(raw: String): DownloadState =
        runCatching {
            val obj = JSONObject(raw)
            val statusName = obj.optString("status", DownloadStatus.IDLE.name)
            val status = runCatching { DownloadStatus.valueOf(statusName) }.getOrDefault(DownloadStatus.IDLE)
            val previewObj = obj.optJSONObject("preview")
            val trackObj = obj.optJSONObject("lastCompletedTrack")
            DownloadState(
                status = status,
                sourceUrl = obj.optString("sourceUrl"),
                title = obj.optString("title").takeIf { it.isNotBlank() },
                progressPercent = obj.optDouble("progressPercent", 0.0).toFloat(),
                downloadedBytes = obj.optLong("downloadedBytes", 0L),
                totalBytes = obj.optLong("totalBytes", 0L),
                speedBytesPerSec = obj.optLong("speedBytesPerSec", 0L),
                statusMessage = obj.optString("statusMessage").takeIf { it.isNotBlank() },
                errorMessage = obj.optString("errorMessage").takeIf { it.isNotBlank() },
                preview = previewObj?.let(::previewFromJsonObject),
                lastCompletedTrack = trackObj?.let(::trackFromJsonObject),
            )
        }.getOrDefault(DownloadState())

    fun engineStatusToJson(status: DownloaderEngineStatus): String =
        JSONObject().apply {
            put("pythonVersion", status.pythonVersion)
            put("ytDlpVersion", status.ytDlpVersion)
            put("ytDlpEjsVersion", status.ytDlpEjsVersion)
            put("quickJsVersion", status.quickJsVersion)
            put("ytDlpUpdatedAt", status.ytDlpUpdatedAt ?: "")
            put("ytDlpEjsUpdatedAt", status.ytDlpEjsUpdatedAt ?: "")
            put("isUpdating", status.isUpdating)
            put("updateMessage", status.updateMessage ?: "")
            put("autoUpdateEnabled", status.autoUpdateEnabled)
            put("preferredQuality", status.preferredQuality.key)
        }.toString()

    fun engineStatusFromJson(raw: String): DownloaderEngineStatus =
        runCatching {
            val obj = JSONObject(raw)
            DownloaderEngineStatus(
                pythonVersion = obj.optString("pythonVersion", "Python 3.11 (ligero)"),
                ytDlpVersion = obj.optString("ytDlpVersion", "No inicializado"),
                ytDlpEjsVersion = obj.optString("ytDlpEjsVersion", "No inicializado"),
                quickJsVersion = obj.optString("quickJsVersion", "QuickJS-NG (Rust)"),
                ytDlpUpdatedAt = obj.optString("ytDlpUpdatedAt").takeIf { it.isNotBlank() },
                ytDlpEjsUpdatedAt = obj.optString("ytDlpEjsUpdatedAt").takeIf { it.isNotBlank() },
                isUpdating = obj.optBoolean("isUpdating", false),
                updateMessage = obj.optString("updateMessage").takeIf { it.isNotBlank() },
                autoUpdateEnabled = obj.optBoolean("autoUpdateEnabled", true),
                preferredQuality = AudioDownloadQuality.fromKey(obj.optString("preferredQuality")),
            )
        }.getOrDefault(DownloaderEngineStatus())

    fun previewToJson(preview: DownloadPreview): String = previewToJsonObject(preview).toString()

    fun previewFromJson(raw: String): DownloadPreview? = runCatching {
        previewFromJsonObject(JSONObject(raw))
    }.getOrNull()

    private fun previewToJsonObject(preview: DownloadPreview): JSONObject {
        val formatsArr = JSONArray().apply {
            preview.availableFormats.forEach { fmt ->
                put(
                    JSONObject().apply {
                        put("formatId", fmt.formatId)
                        put("extension", fmt.extension)
                        put("codec", fmt.codec)
                        put("bitrateKbps", fmt.bitrateKbps)
                        put("estimatedSizeBytes", fmt.estimatedSizeBytes)
                        put("label", fmt.label)
                    },
                )
            }
        }
        return JSONObject().apply {
            put("sourceUrl", preview.sourceUrl)
            put("videoId", preview.videoId)
            put("title", preview.title)
            put("channelOrArtist", preview.channelOrArtist)
            put("durationMs", preview.durationMs)
            put("estimatedSizeBytes", preview.estimatedSizeBytes)
            put("audioFormatLabel", preview.audioFormatLabel)
            put("localThumbnailPath", preview.localThumbnailPath ?: "")
            put("availableFormats", formatsArr)
            put("selectedFormatId", preview.selectedFormatId ?: "")
        }
    }

    private fun previewFromJsonObject(obj: JSONObject): DownloadPreview {
        val formatsArr = obj.optJSONArray("availableFormats")
        val formats = if (formatsArr != null) {
            buildList(formatsArr.length()) {
                for (i in 0 until formatsArr.length()) {
                    val item = formatsArr.optJSONObject(i) ?: continue
                    val id = item.optString("formatId")
                    if (id.isBlank()) continue
                    add(
                        DownloadAudioFormatOption(
                            formatId = id,
                            extension = item.optString("extension", "audio"),
                            codec = item.optString("codec", ""),
                            bitrateKbps = item.optInt("bitrateKbps", 0),
                            estimatedSizeBytes = item.optLong("estimatedSizeBytes", 0L),
                            label = item.optString("label", ""),
                        ),
                    )
                }
            }
        } else {
            emptyList()
        }
        return DownloadPreview(
            sourceUrl = obj.optString("sourceUrl"),
            videoId = obj.optString("videoId"),
            title = obj.optString("title", "Audio sin título"),
            channelOrArtist = obj.optString("channelOrArtist", "Descarga web"),
            durationMs = obj.optLong("durationMs", 0L),
            estimatedSizeBytes = obj.optLong("estimatedSizeBytes", 0L),
            audioFormatLabel = obj.optString("audioFormatLabel"),
            localThumbnailPath = obj.optString("localThumbnailPath").takeIf { it.isNotBlank() },
            availableFormats = formats,
            selectedFormatId = obj.optString("selectedFormatId").takeIf { it.isNotBlank() },
        )
    }

    private fun trackToJsonObject(track: Track): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("contentUri", track.contentUri)
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("durationMs", track.durationMs)
        put("coverFilePath", track.coverFilePath ?: "")
        put("isProceduralCover", track.isProceduralCover)
        put("isLiked", track.isLiked)
        put("trackFingerprint", track.trackFingerprint)
        put("addedAt", track.addedAt)
    }

    private fun trackFromJsonObject(obj: JSONObject): Track = Track(
        id = obj.optLong("id", 0L),
        contentUri = obj.optString("contentUri"),
        title = obj.optString("title"),
        artist = obj.optString("artist"),
        album = obj.optString("album"),
        durationMs = obj.optLong("durationMs", 0L),
        coverFilePath = obj.optString("coverFilePath").takeIf { it.isNotBlank() },
        isProceduralCover = obj.optBoolean("isProceduralCover", false),
        isLiked = obj.optBoolean("isLiked", false),
        trackFingerprint = obj.optString("trackFingerprint"),
        addedAt = obj.optLong(
            "addedAt",
            obj.optLong("addedAtEpochMs", System.currentTimeMillis()),
        ),
    )
}

data class CachedEngineVersions(
    val pythonVersion: String,
    val ytDlpVersion: String,
    val ytDlpEjsVersion: String,
    val ytDlpUpdatedAt: String?,
    val ytDlpEjsUpdatedAt: String?,
)

/**
 * Descriptor escrito en disco por `:downloader` tras finalizar una descarga para que el proceso
 * principal (`MAIN`) realice la indexación única en Room sin acceso concurrente multi-proceso.
 */
data class PendingDownloadedImport(
    val sourceUrl: String,
    val audioPath: String,
    val thumbnailPath: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val accentArgb: Int,
) {
    fun toJson(): String = JSONObject().apply {
        put("sourceUrl", sourceUrl)
        put("audioPath", audioPath)
        put("thumbnailPath", thumbnailPath ?: "")
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMs", durationMs)
        put("accentArgb", accentArgb)
    }.toString()

    companion object {
        fun fromJson(raw: String): PendingDownloadedImport? = runCatching {
            val obj = JSONObject(raw)
            val audioPath = obj.optString("audioPath")
            if (audioPath.isBlank()) return null
            PendingDownloadedImport(
                sourceUrl = obj.optString("sourceUrl"),
                audioPath = audioPath,
                thumbnailPath = obj.optString("thumbnailPath").takeIf { it.isNotBlank() },
                title = obj.optString("title", "Audio descargado"),
                artist = obj.optString("artist", "Descarga web"),
                album = obj.optString("album", "Descargas Aura"),
                durationMs = obj.optLong("durationMs", 0L),
                accentArgb = obj.optInt("accentArgb", 0xFFA78BFA.toInt()),
            )
        }.getOrNull()
    }
}
