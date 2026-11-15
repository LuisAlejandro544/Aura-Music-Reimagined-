package com.auramusic.core.importer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.StoragePreferencesRepository
import com.auramusic.core.model.SystemFolderHelper
import com.auramusic.core.model.SystemFolderStorageState
import com.auramusic.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Administrador de la carpeta pública opcional `Aura Music` en el almacenamiento del sistema.
 *
 * Por defecto esta función permanece desactivada (`false`). Cuando el usuario la activa desde
 * Ajustes y concede permiso de almacenamiento:
 * 1. Crea `Aura Music/Música`, `Aura Music/Carátulas` (con `.nomedia` para proteger la galería)
 *    y archivos `.json` (`aura_biblioteca.json`, `aura_player_config.json`, `engine_versions.json`).
 * 2. Transfiere las canciones existentes, sus carátulas y configuraciones JSON desde la memoria
 *    interna hacia `Aura Music`, actualizando Room sin perder playlists ni "Me gusta".
 * 3. Escanea automáticamente `Aura Music/Música` (y archivos de audio colocados en `Aura Music/`)
 *    al entrar a la app para importar mediante Rust cualquier canción nueva agregada manualmente.
 */
class AuraSystemStorageManager(
    private val context: Context,
    private val repository: MusicRepository,
    private val storagePreferencesRepository: StoragePreferencesRepository,
    private val audioImporter: AudioImporter,
) {
    private val syncMutex = Mutex()
    private val _state = MutableStateFlow(
        SystemFolderStorageState(
            isEnabled = false,
            hasStoragePermission = hasStoragePermission(context),
        ),
    )
    val state: StateFlow<SystemFolderStorageState> = _state.asStateFlow()

    /**
     * Refresca en memoria el estado actual de activación y permisos sin bloquear el hilo principal.
     */
    suspend fun refreshState() = withContext(Dispatchers.IO) {
        val enabled = storagePreferencesRepository.systemFolderEnabled.first()
        val granted = hasStoragePermission(context)
        _state.value = _state.value.copy(
            isEnabled = enabled,
            hasStoragePermission = granted,
        )
    }

    /**
     * Activa la carpeta pública `Aura Music`, crea las subcarpetas, migra las canciones/carátulas
     * y archivos `.json` existentes, y escanea posibles pistas ya colocadas en la carpeta.
     */
    suspend fun enableAndMigrate(accentArgb: Int): SystemFolderStorageState = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val granted = hasStoragePermission(context)
            if (!granted) {
                storagePreferencesRepository.setSystemFolderEnabled(false)
                val denied = SystemFolderStorageState(
                    isEnabled = false,
                    hasStoragePermission = false,
                    isBusy = false,
                    statusMessage = "Se requiere permiso de almacenamiento para crear la carpeta Aura Music.",
                )
                _state.value = denied
                return@withContext denied
            }

            if (!ensureFolderStructureInternal()) {
                val failed = SystemFolderStorageState(
                    isEnabled = false,
                    hasStoragePermission = true,
                    isBusy = false,
                    statusMessage = "No se pudo crear la carpeta Aura Music en el almacenamiento del dispositivo.",
                )
                _state.value = failed
                return@withContext failed
            }

            storagePreferencesRepository.setSystemFolderEnabled(true)

            val allTracks = repository.getAllTracksOnce()
            _state.value = SystemFolderStorageState(
                isEnabled = true,
                hasStoragePermission = true,
                isBusy = true,
                processedCount = 0,
                totalCount = allTracks.size,
                statusMessage = "Transfiriendo música, carátulas y archivos .json a Aura Music...",
            )

            val migratedCount = migrateExistingTracksInternal(allTracks)
            audioImporter.cleanupOrphanCovers()
            val discoveredCount = scanNewTracksInternal(accentArgb)
            syncJsonFilesInternal()

            val summary = buildString {
                append("Carpeta Aura Music lista")
                if (migratedCount > 0) {
                    append(" • $migratedCount ")
                    append(if (migratedCount == 1) "canción traspasada" else "canciones traspasadas")
                }
                if (discoveredCount > 0) {
                    append(" • $discoveredCount ")
                    append(if (discoveredCount == 1) "nueva detectada" else "nuevas detectadas")
                }
            }

            val done = SystemFolderStorageState(
                isEnabled = true,
                hasStoragePermission = true,
                isBusy = false,
                processedCount = allTracks.size,
                totalCount = allTracks.size,
                lastSyncedCount = discoveredCount,
                statusMessage = summary,
            )
            _state.value = done
            done
        }
    }

    /**
     * Desactiva la sincronización automática con la carpeta del sistema sin borrar los archivos
     * existentes dentro de `Aura Music/`.
     */
    suspend fun disableSystemFolder() = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            storagePreferencesRepository.setSystemFolderEnabled(false)
            _state.value = _state.value.copy(
                isEnabled = false,
                hasStoragePermission = hasStoragePermission(context),
                isBusy = false,
                statusMessage = "Carpeta del sistema desactivada. Tus archivos existentes en Aura Music se conservan intactos.",
            )
        }
    }

    /**
     * Escanea `Aura Music/Música` (y archivos de audio colocados en `Aura Music/`) e importa
     * cualquier canción nueva que el usuario haya copiado desde el explorador del teléfono.
     * También asegura que los archivos `.json` estén actualizados.
     */
    suspend fun syncOnAppEntryOrManual(
        accentArgb: Int,
        migrateRemainingTracks: Boolean = false,
    ): Int = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            val enabled = storagePreferencesRepository.systemFolderEnabled.first()
            val granted = hasStoragePermission(context)
            if (!enabled || !granted) {
                _state.value = _state.value.copy(
                    isEnabled = enabled,
                    hasStoragePermission = granted,
                    isBusy = false,
                )
                return@withContext 0
            }

            if (!ensureFolderStructureInternal()) {
                return@withContext 0
            }

            var migratedCount = 0
            if (migrateRemainingTracks) {
                val allTracks = repository.getAllTracksOnce()
                _state.value = _state.value.copy(
                    isEnabled = true,
                    hasStoragePermission = true,
                    isBusy = true,
                    processedCount = 0,
                    totalCount = allTracks.size,
                    statusMessage = "Sincronizando biblioteca con Aura Music...",
                )
                migratedCount = migrateExistingTracksInternal(allTracks)
                audioImporter.cleanupOrphanCovers()
            } else {
                _state.value = _state.value.copy(
                    isEnabled = true,
                    hasStoragePermission = true,
                    isBusy = true,
                    statusMessage = "Buscando canciones nuevas en Aura Music/Música...",
                )
            }

            val discoveredCount = scanNewTracksInternal(accentArgb)
            syncJsonFilesInternal()

            val summary = when {
                migrateRemainingTracks && migratedCount > 0 && discoveredCount > 0 ->
                    "Sincronización completa: $migratedCount traspasadas y $discoveredCount nuevas."
                migrateRemainingTracks && migratedCount > 0 ->
                    "Se traspasaron $migratedCount canciones pendientes a Aura Music."
                discoveredCount > 0 ->
                    "Se importaron $discoveredCount canciones nuevas desde Aura Music."
                migrateRemainingTracks ->
                    "Todo está sincronizado con Aura Music."
                else -> _state.value.statusMessage
            }

            _state.value = _state.value.copy(
                isEnabled = true,
                hasStoragePermission = true,
                isBusy = false,
                lastSyncedCount = discoveredCount,
                statusMessage = summary,
            )
            discoveredCount
        }
    }

    /**
     * Sincroniza los archivos `.json` de configuración y respaldo de biblioteca en `Aura Music/`
     * únicamente cuando el modo de carpeta del sistema está habilitado y autorizado.
     */
    suspend fun syncJsonSnapshotsIfEnabled() = withContext(Dispatchers.IO) {
        val enabled = storagePreferencesRepository.systemFolderEnabled.first()
        if (!enabled || !hasStoragePermission(context)) return@withContext
        if (ensureFolderStructureInternal()) {
            syncJsonFilesInternal()
        }
    }

    fun clearStatusMessage() {
        _state.value = _state.value.copy(statusMessage = null)
    }

    private fun ensureFolderStructureInternal(): Boolean {
        val rootDir = getRootDir()
        val musicDir = getMusicDir()
        val coversDir = getCoversDir()
        val created = runCatching {
            val rootOk = rootDir.exists() || rootDir.mkdirs()
            val musicOk = musicDir.exists() || musicDir.mkdirs()
            val coversOk = coversDir.exists() || coversDir.mkdirs()
            if (coversOk) {
                val noMedia = File(coversDir, ".nomedia")
                if (!noMedia.exists()) {
                    runCatching { noMedia.createNewFile() }
                }
            }
            rootOk && musicOk && coversOk
        }.getOrDefault(false)
        return created && rootDir.isDirectory && musicDir.isDirectory && coversDir.isDirectory
    }

    private suspend fun migrateExistingTracksInternal(allTracks: List<Track>): Int {
        if (allTracks.isEmpty()) return 0
        val musicDir = getMusicDir()
        val coversDir = getCoversDir()
        val musicCanonical = runCatching { musicDir.canonicalPath }.getOrNull() ?: musicDir.absolutePath
        val coversCanonical = runCatching { coversDir.canonicalPath }.getOrNull() ?: coversDir.absolutePath
        val ownedDownloadDir = runCatching { File(context.filesDir, "downloads").canonicalPath }.getOrNull()
        val ownedOpenedDir = runCatching { File(context.filesDir, "opened_audio").canonicalPath }.getOrNull()

        var migratedCount = 0

        allTracks.forEachIndexed { index, track ->
            val currentUri = runCatching { Uri.parse(track.contentUri) }.getOrNull()
            var nextContentUri = track.contentUri
            var oldManagedAudioToDelete: File? = null

            val alreadyInMusicFolder = if (currentUri?.scheme == "file" && currentUri.path != null) {
                val canonical = runCatching { File(currentUri.path!!).canonicalPath }.getOrNull()
                canonical != null && canonical.startsWith(musicCanonical + File.separator)
            } else {
                false
            }

            if (!alreadyInMusicFolder && currentUri != null) {
                val ext = resolveExtensionForUri(currentUri)
                val destFile = resolveUniqueAudioDestFile(musicDir, track, ext)
                val copied = copyUriToFileAtomically(currentUri, destFile)
                if (copied) {
                    nextContentUri = Uri.fromFile(destFile).toString()
                    scanMediaFile(destFile)

                    if (currentUri.scheme == "file" && currentUri.path != null) {
                        val oldFile = File(currentUri.path!!)
                        val oldCanonical = runCatching { oldFile.canonicalPath }.getOrNull()
                        val isOwned = oldCanonical != null && (
                            (ownedDownloadDir != null && oldCanonical.startsWith(ownedDownloadDir + File.separator)) ||
                                (ownedOpenedDir != null && oldCanonical.startsWith(ownedOpenedDir + File.separator))
                            )
                        if (isOwned && oldCanonical != runCatching { destFile.canonicalPath }.getOrNull()) {
                            oldManagedAudioToDelete = oldFile
                        }
                    }
                }
            }

            var nextCoverPath = track.coverFilePath
            if (!nextCoverPath.isNullOrBlank()) {
                val srcCover = File(nextCoverPath)
                if (srcCover.exists() && srcCover.length() > 0L) {
                    val srcCanonical = runCatching { srcCover.canonicalPath }.getOrNull()
                    val alreadyInCoversFolder = srcCanonical != null &&
                        srcCanonical.startsWith(coversCanonical + File.separator)
                    if (!alreadyInCoversFolder) {
                        val destCover = File(coversDir, srcCover.name)
                        if (copyFileAtomically(srcCover, destCover)) {
                            nextCoverPath = destCover.absolutePath
                        }
                    }
                }
            }

            if (nextContentUri != track.contentUri || nextCoverPath != track.coverFilePath) {
                val updated = repository.updateTrackStorageLocation(
                    trackId = track.id,
                    newContentUri = nextContentUri,
                    newCoverPath = nextCoverPath,
                )
                if (updated) {
                    migratedCount++
                    oldManagedAudioToDelete?.let { oldFile ->
                        if (oldFile.exists()) {
                            runCatching { oldFile.delete() }
                        }
                    }
                }
            }

            _state.value = _state.value.copy(
                processedCount = index + 1,
                totalCount = allTracks.size,
            )
        }

        return migratedCount
    }

    private suspend fun scanNewTracksInternal(accentArgb: Int): Int {
        val rootDir = getRootDir()
        val musicDir = getMusicDir()
        val coversDir = getCoversDir()
        val discoveredFiles = linkedMapOf<String, File>()

        // Escanea recursivamente `Aura Music/Música` y también archivos sueltos en `Aura Music/`.
        collectAudioFilesRecursively(musicDir, discoveredFiles)
        rootDir.listFiles()?.forEach { child ->
            if (child.isFile && SystemFolderHelper.isSupportedAudioFileName(child.name)) {
                val key = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                discoveredFiles[key] = child
            }
        }

        if (discoveredFiles.isEmpty()) return 0

        val (dbUris, dbFingerprints) = repository.getAllTrackIdentities()
        val seenUris = dbUris.toMutableSet()
        val seenFingerprints = dbFingerprints.toMutableSet()
        val tracksToInsert = mutableListOf<Track>()

        for (file in discoveredFiles.values) {
            val fileUri = Uri.fromFile(file).toString()
            val canonicalUri = runCatching { Uri.fromFile(file.canonicalFile).toString() }.getOrNull()
            if (fileUri in seenUris || (canonicalUri != null && canonicalUri in seenUris)) {
                continue
            }

            val parsed = audioImporter.extractTrackFromLocalFile(
                audioFile = file,
                coversDirOverride = coversDir,
                accentArgb = accentArgb,
            ) ?: continue

            if (seenFingerprints.add(parsed.trackFingerprint) && seenUris.add(parsed.contentUri)) {
                tracksToInsert.add(parsed)
            }
        }

        if (tracksToInsert.isNotEmpty()) {
            repository.insertTracks(tracksToInsert)
        }
        return tracksToInsert.size
    }

    private fun collectAudioFilesRecursively(dir: File, out: MutableMap<String, File>) {
        if (!dir.exists() || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                collectAudioFilesRecursively(child, out)
            } else if (child.isFile && SystemFolderHelper.isSupportedAudioFileName(child.name)) {
                val key = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                out[key] = child
            }
        }
    }

    private suspend fun syncJsonFilesInternal() {
        val rootDir = getRootDir()

        // 1. Espejo de configuración de reproducción/ecualizador/efectos (`aura_player_config.json`).
        val internalPlayerConfig = File(context.filesDir, SystemFolderHelper.PLAYER_CONFIG_JSON_FILE_NAME)
        if (internalPlayerConfig.exists() && internalPlayerConfig.length() > 0L) {
            copyFileAtomically(
                src = internalPlayerConfig,
                dst = File(rootDir, SystemFolderHelper.PLAYER_CONFIG_JSON_FILE_NAME),
            )
        }

        // 2. Espejo de versiones del motor de descargas si existe (`py_updates/engine_versions.json`).
        val internalVersionsJson = File(
            File(context.filesDir, "py_updates"),
            SystemFolderHelper.ENGINE_VERSIONS_JSON_FILE_NAME,
        ).takeIf { it.exists() && it.length() > 0L }
            ?: File(context.filesDir, SystemFolderHelper.ENGINE_VERSIONS_JSON_FILE_NAME)
        if (internalVersionsJson.exists() && internalVersionsJson.length() > 0L) {
            copyFileAtomically(
                src = internalVersionsJson,
                dst = File(rootDir, SystemFolderHelper.ENGINE_VERSIONS_JSON_FILE_NAME),
            )
        }

        // 3. Respaldo legible de biblioteca, carátulas y playlists (`aura_biblioteca.json`).
        val tracks = repository.getAllTracksOnce()
        val (playlists, crossRefs) = repository.getPlaylistsSnapshotOnce()
        val tracksById = tracks.associateBy { it.id }

        val jsonRoot = JSONObject().apply {
            put("schemaVersion", 1)
            put("updatedAt", System.currentTimeMillis())
            put("rootFolder", rootDir.absolutePath)
            put("musicFolder", getMusicDir().absolutePath)
            put("coversFolder", getCoversDir().absolutePath)
            put(
                "tracks",
                JSONArray().apply {
                    for (track in tracks) {
                        val audioFileName = runCatching {
                            Uri.parse(track.contentUri).path?.let { File(it).name }
                        }.getOrNull().orEmpty()
                        val coverFileName = track.coverFilePath?.let { File(it).name }.orEmpty()
                        put(
                            JSONObject().apply {
                                put("id", track.id)
                                put("title", track.title)
                                put("artist", track.artist)
                                put("album", track.album)
                                put("durationMs", track.durationMs)
                                put("fileName", audioFileName)
                                put("contentUri", track.contentUri)
                                put("coverFileName", coverFileName)
                                put("isProceduralCover", track.isProceduralCover)
                                put("isLiked", track.isLiked)
                                put("trackFingerprint", track.trackFingerprint)
                                put("addedAt", track.addedAt)
                            },
                        )
                    }
                },
            )
            put(
                "playlists",
                JSONArray().apply {
                    val refsByPlaylist = crossRefs.groupBy { it.playlistId }
                    for (playlist in playlists) {
                        val trackFingerprints = JSONArray().apply {
                            refsByPlaylist[playlist.id].orEmpty().forEach { ref ->
                                tracksById[ref.trackId]?.trackFingerprint?.let(::put)
                            }
                        }
                        put(
                            JSONObject().apply {
                                put("id", playlist.id)
                                put("name", playlist.name)
                                put("createdAt", playlist.createdAt)
                                put("trackFingerprints", trackFingerprints)
                            },
                        )
                    }
                },
            )
        }

        writeTextAtomically(
            target = File(rootDir, SystemFolderHelper.LIBRARY_JSON_FILE_NAME),
            content = jsonRoot.toString(2),
        )
    }

    private fun resolveUniqueAudioDestFile(
        musicDir: File,
        track: Track,
        extension: String,
    ): File {
        val primaryName = SystemFolderHelper.buildTrackAudioFileName(
            title = track.title,
            artist = track.artist,
            extension = extension,
            fingerprint = track.trackFingerprint,
            disambiguate = false,
        )
        val primary = File(musicDir, primaryName)
        if (!primary.exists()) return primary

        val disambiguatedName = SystemFolderHelper.buildTrackAudioFileName(
            title = track.title,
            artist = track.artist,
            extension = extension,
            fingerprint = track.trackFingerprint,
            disambiguate = true,
        )
        val secondary = File(musicDir, disambiguatedName)
        if (!secondary.exists()) return secondary

        val base = secondary.nameWithoutExtension
        val ext = secondary.extension
        var counter = 2
        while (counter < 1000) {
            val candidate = File(musicDir, "${base}_$counter.$ext")
            if (!candidate.exists()) return candidate
            counter++
        }
        return File(musicDir, "${base}_${System.currentTimeMillis()}.$ext")
    }

    private fun resolveExtensionForUri(uri: Uri): String {
        if (uri.scheme == "file") {
            val ext = uri.path?.let { File(it).extension }?.lowercase().orEmpty()
            if (ext.isNotBlank()) return ext
        }
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val extFromName = docFile?.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (extFromName.length in 2..6 && extFromName.all(Char::isLetterOrDigit)) {
            return extFromName
        }
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extFromMime = if (mime.isNotBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
        } else {
            ""
        }
        return extFromMime.takeIf { it.length in 2..6 && it.all(Char::isLetterOrDigit) } ?: "mp3"
    }

    private fun copyUriToFileAtomically(sourceUri: Uri, destFile: File): Boolean {
        val tmp = File(destFile.parentFile, ".${destFile.name}.tmp")
        return runCatching {
            val input = if (sourceUri.scheme == "file") {
                val path = sourceUri.path ?: return@runCatching false
                val srcFile = File(path)
                if (!srcFile.exists() || srcFile.length() <= 0L) return@runCatching false
                FileInputStream(srcFile)
            } else {
                context.contentResolver.openInputStream(sourceUri) ?: return@runCatching false
            }

            val copiedBytes = input.use { stream ->
                FileOutputStream(tmp).use { out ->
                    stream.copyTo(out, bufferSize = 32 * 1024)
                }
            }
            if (copiedBytes <= 0L) {
                tmp.delete()
                return@runCatching false
            }
            if (destFile.exists()) destFile.delete()
            if (!tmp.renameTo(destFile)) {
                tmp.copyTo(destFile, overwrite = true)
                tmp.delete()
            }
            destFile.exists() && destFile.length() > 0L
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
    }

    private fun copyFileAtomically(src: File, dst: File): Boolean {
        if (!src.exists() || src.length() <= 0L) return false
        val srcCanonical = runCatching { src.canonicalPath }.getOrNull()
        val dstCanonical = runCatching { dst.canonicalPath }.getOrNull()
        if (srcCanonical != null && srcCanonical == dstCanonical) return true

        val tmp = File(dst.parentFile, ".${dst.name}.tmp")
        return runCatching {
            FileInputStream(src).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out, bufferSize = 32 * 1024)
                }
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
            dst.exists() && dst.length() > 0L
        }.getOrElse {
            runCatching { tmp.delete() }
            false
        }
    }

    private fun writeTextAtomically(target: File, content: String) {
        runCatching {
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.writeText(content)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }

    private fun scanMediaFile(file: File) {
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null,
                null,
            )
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun getRootDir(): File =
            File(Environment.getExternalStorageDirectory(), SystemFolderHelper.ROOT_FOLDER_NAME)

        fun getMusicDir(): File =
            File(getRootDir(), SystemFolderHelper.MUSIC_SUBFOLDER_NAME)

        fun getCoversDir(): File =
            File(getRootDir(), SystemFolderHelper.COVERS_SUBFOLDER_NAME)

        /**
         * Verifica si la app cuenta con acceso de escritura a la carpeta pública `Aura Music`
         * en Android 8–10 (`READ/WRITE_EXTERNAL_STORAGE`) y Android 11–15 (`MANAGE_EXTERNAL_STORAGE`).
         */
        fun hasStoragePermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                val readOk = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
                val writeOk = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
                readOk && writeOk
            }
        }

        /**
         * Construye el Intent del sistema para conceder acceso total a archivos en Android 11+ (API 30+),
         * con respaldo seguro en caso de ROMs personalizadas.
         */
        fun buildManageAllFilesIntent(context: Context): Intent {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
        }

        fun legacyStoragePermissions(): Array<String> = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
    }
}
