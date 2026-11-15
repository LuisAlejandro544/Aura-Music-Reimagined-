package com.auramusic.core.importer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.database.StoragePreferencesRepository
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.SystemFolderHelper
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Motor de importación manual de música local para Aura Music.
 *
 * Soporta tres flujos explícitos iniciados por el usuario:
 * 1. Importación individual (1 canción).
 * 2. Importación por tandas (selección múltiple de canciones).
 * 3. Importación de una carpeta completa (recorrido recursivo con DocumentFile).
 *
 * Todo el recorrido de archivos, lectura de etiquetas ID3/Vorbis/FLAC/MP4 en Rust (`lofty`),
 * extracción de carátulas embebidas y generación procedural se ejecuta fuera del hilo principal
 * ([Dispatchers.IO] / [Dispatchers.Default]).
 */
class AudioImporter(
    private val context: Context,
    private val repository: MusicRepository,
    private val storagePreferencesRepository: StoragePreferencesRepository? = null,
) {
    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()
    private val coverWriteMutex = Mutex()

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "alac", "aiff",
    )

    /**
     * Importa uno o varios archivos de audio seleccionados mediante el selector de documentos.
     */
    suspend fun importDocumentUris(uris: List<Uri>, accentArgb: Int) {
        if (uris.isEmpty()) return
        withContext(Dispatchers.IO) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            processAudioUris(
                uris = uris,
                accentArgb = accentArgb,
                treeUriPersisted = false,
            )
        }
    }

    /**
     * Importa de forma recursiva todos los audios dentro de una carpeta seleccionada por el usuario.
     */
    suspend fun importFolderTree(treeUri: Uri, accentArgb: Int) {
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val treePersisted = hasPersistedReadPermission(treeUri)

            _progress.value = ImportProgress(
                isImporting = true,
                lastMessage = "Analizando carpeta seleccionada...",
            )

            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (rootDoc == null || !rootDoc.isDirectory) {
                _progress.value = ImportProgress(
                    isImporting = false,
                    lastMessage = "No se pudo abrir la carpeta seleccionada.",
                )
                return@withContext
            }

            val audioUris = mutableListOf<Uri>()
            collectAudioFilesRecursively(rootDoc, audioUris)
            processAudioUris(
                uris = audioUris,
                accentArgb = accentArgb,
                treeUriPersisted = treePersisted,
            )
        }
    }

    fun clearLastMessage() {
        _progress.value = _progress.value.copy(lastMessage = null)
    }

    /**
     * Elimina archivos de carátula huérfanos en `filesDir/covers` (y en `Aura Music/Carátulas`
     * si la carpeta pública está activa) que ya no pertenezcan a ninguna canción registrada en Room.
     */
    suspend fun cleanupOrphanCovers(): Int = withContext(Dispatchers.IO) {
        val candidateDirs = buildList {
            add(File(context.filesDir, "covers"))
            if (isSystemFolderActive()) {
                add(AuraSystemStorageManager.getCoversDir())
            }
        }

        val referencedPaths = repository.getAllReferencedCoverPaths()
            .mapNotNull { path -> runCatching { File(path).canonicalPath }.getOrNull() }
            .toSet()

        var removed = 0
        for (dir in candidateDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.startsWith(".")) {
                    val canonical = runCatching { file.canonicalPath }.getOrNull()
                    if (canonical != null && canonical !in referencedPaths) {
                        if (runCatching { file.delete() }.getOrDefault(false)) {
                            removed++
                        }
                    }
                }
            }
        }
        removed
    }

    /**
     * Importa un archivo de audio descargado localmente por `:core:downloader` hacia Room.
     * Si la carpeta del sistema `Aura Music` está activa, traslada automáticamente la descarga a
     * `Aura Music/Música` y guarda su carátula en `Aura Music/Carátulas`.
     */
    suspend fun importDownloadedAudioFile(
        audioFile: File,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long,
        thumbnailFile: File?,
        accentArgb: Int,
    ): Track? = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() <= 0L) return@withContext null

        val systemFolderActive = isSystemFolderActive()
        val coversDir = resolveCoversDirectory(systemFolderActive)
        val (dbUris, dbFingerprints) = repository.getAllTrackIdentities()

        val metadata = runCatching {
            android.os.ParcelFileDescriptor.open(
                audioFile,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            ).use { pfd ->
                AuraRustBridge.extractAudioMetadata(pfd.fd)
            }
        }.getOrNull()

        val title = metadata?.title?.takeIf { it.isNotBlank() }
            ?: fallbackTitle.takeIf { it.isNotBlank() }
            ?: audioFile.nameWithoutExtension
        val artist = metadata?.artist?.takeIf { it.isNotBlank() }
            ?: fallbackArtist.takeIf { it.isNotBlank() }
            ?: "Artista desconocido"
        val album = metadata?.album?.takeIf { it.isNotBlank() }
            ?: fallbackAlbum.takeIf { it.isNotBlank() }
            ?: "Descargas Aura"
        val durationMs = (metadata?.durationMs ?: 0L).takeIf { it > 0L }
            ?: fallbackDurationMs.coerceAtLeast(0L)

        val fingerprint = AuraRustBridge.computeTrackFingerprint(
            title = title,
            artist = artist,
            durationMs = durationMs,
            sizeBytes = audioFile.length(),
        )

        val finalAudioFile = if (systemFolderActive) {
            moveFileIntoSystemMusicFolder(
                sourceFile = audioFile,
                title = title,
                artist = artist,
                fingerprint = fingerprint,
            ) ?: audioFile
        } else {
            audioFile
        }

        val contentUri = Uri.fromFile(finalAudioFile).toString()
        if (contentUri in dbUris || fingerprint in dbFingerprints) {
            val existing = repository.findTrackByUriOrFingerprint(contentUri, fingerprint)
            if (existing != null) {
                // Evita dejar archivos duplicados si la pista ya estaba registrada con otra ruta.
                if (existing.contentUri != contentUri && finalAudioFile.exists()) {
                    runCatching { finalAudioFile.delete() }
                }
                if (thumbnailFile != null && thumbnailFile.exists()) {
                    runCatching { thumbnailFile.delete() }
                }
                return@withContext existing
            }
        }

        val embeddedBytes = metadata?.embeddedCoverBytes
        val (coverPath, isProcedural) = coverWriteMutex.withLock {
            if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                saveEmbeddedCover(fingerprint, embeddedBytes, coversDir) to false
            } else if (thumbnailFile != null && thumbnailFile.exists() && thumbnailFile.length() > 0L) {
                val webpPath = saveCustomCoverAsLosslessWebp(
                    trackId = System.currentTimeMillis(),
                    imageUri = Uri.fromFile(thumbnailFile),
                )
                if (webpPath != null) {
                    webpPath to false
                } else {
                    val seedKey = "$title|$artist|$album|$fingerprint"
                    val procPath = AuraRustBridge.generateAndSaveProceduralCover(
                        context = context,
                        fingerprint = fingerprint,
                        seedKey = seedKey,
                        accentArgb = accentArgb,
                        outputDirectory = coversDir,
                    )
                    procPath to true
                }
            } else {
                val seedKey = "$title|$artist|$album|$fingerprint"
                val procPath = AuraRustBridge.generateAndSaveProceduralCover(
                    context = context,
                    fingerprint = fingerprint,
                    seedKey = seedKey,
                    accentArgb = accentArgb,
                    outputDirectory = coversDir,
                )
                procPath to true
            }
        }

        if (thumbnailFile != null && thumbnailFile.exists()) {
            runCatching { thumbnailFile.delete() }
        }

        val track = Track(
            contentUri = contentUri,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            coverFilePath = coverPath,
            isProceduralCover = isProcedural,
            isLiked = false,
            trackFingerprint = fingerprint,
        )
        // Retorna la entidad ya insertada con su ID real en Room para que "Reproducir ahora" funcione de inmediato.
        repository.insertTrackAndGet(track)
    }

    /**
     * Importa o recupera una canción abierta desde otra aplicación mediante "Abrir con"
     * (`Intent.ACTION_VIEW`) con soporte para esquemas `content://` y `file://`.
     *
     * Si el proveedor entrega un permiso temporal (`content://` sin permiso persistible),
     * copia el audio al directorio privado `filesDir/opened_audio/` para evitar `SecurityException`
     * cuando el Activity externo cierre el grant, evitando siempre duplicados en Room.
     */
    suspend fun importExternalAudioUri(
        uri: Uri,
        accentArgb: Int,
    ): Track? = withContext(Dispatchers.IO) {
        var resolvedUri = uri
        var copiedLocalFile: File? = null

        val hasPersistableGrant = if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } else {
            true
        }

        if (uri.scheme == "content" && !hasPersistableGrant) {
            copiedLocalFile = copyExternalUriToOpenedAudio(uri)
            if (copiedLocalFile != null) {
                resolvedUri = Uri.fromFile(copiedLocalFile)
            }
        }

        val parsed = extractTrackWithCover(resolvedUri, accentArgb)
        if (parsed == null) {
            if (copiedLocalFile != null && copiedLocalFile.exists()) {
                runCatching { copiedLocalFile.delete() }
            }
            return@withContext null
        }

        val existing = repository.findTrackByUriOrFingerprint(
            uri = parsed.contentUri,
            fingerprint = parsed.trackFingerprint,
        ) ?: repository.findTrackByUriOrFingerprint(
            uri = uri.toString(),
            fingerprint = parsed.trackFingerprint,
        )

        if (existing != null) {
            if (copiedLocalFile != null &&
                copiedLocalFile.exists() &&
                existing.contentUri != Uri.fromFile(copiedLocalFile).toString()
            ) {
                runCatching { copiedLocalFile.delete() }
            }
            return@withContext existing
        }

        val systemFolderActive = isSystemFolderActive()
        val finalizedFile = if (copiedLocalFile != null && copiedLocalFile.exists()) {
            val ext = copiedLocalFile.extension.ifBlank { "audio" }
            if (systemFolderActive) {
                moveFileIntoSystemMusicFolder(
                    sourceFile = copiedLocalFile,
                    title = parsed.title,
                    artist = parsed.artist,
                    fingerprint = parsed.trackFingerprint,
                ) ?: copiedLocalFile
            } else {
                val stableFile = File(
                    File(context.filesDir, "opened_audio").apply { mkdirs() },
                    "open_${parsed.trackFingerprint.take(24)}.$ext",
                )
                if (stableFile != copiedLocalFile) {
                    if (stableFile.exists() || copiedLocalFile.renameTo(stableFile)) {
                        if (copiedLocalFile.exists() && copiedLocalFile != stableFile) {
                            runCatching { copiedLocalFile.delete() }
                        }
                        stableFile
                    } else {
                        copiedLocalFile
                    }
                } else {
                    copiedLocalFile
                }
            }
        } else {
            null
        }

        val trackToSave = if (finalizedFile != null) {
            parsed.copy(contentUri = Uri.fromFile(finalizedFile).toString())
        } else {
            parsed
        }

        repository.insertTrackAndGet(trackToSave)
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        uri.scheme != "content" ||
            context.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }

    private fun copyExternalUriToOpenedAudio(uri: Uri): File? =
        copyContentUriToManagedDir(uri, dirName = "opened_audio")

    /**
     * Copia una URI `content://` sin permiso persistible hacia un directorio privado administrado
     * por la app (`imported_audio` u `opened_audio`) para garantizar que el proceso aislado `:player`
     * pueda leerla en cualquier momento sin `SecurityException`.
     */
    private fun copyContentUriToManagedDir(uri: Uri, dirName: String): File? {
        val targetDir = File(context.filesDir, dirName).apply { mkdirs() }
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extFromName = docFile?.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val extFromMime = if (mime.isNotBlank()) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime).orEmpty()
        } else {
            ""
        }
        val extension = (extFromName.ifBlank { extFromMime }.ifBlank { "mp3" })
            .takeIf { it.length in 2..6 && it.all(Char::isLetterOrDigit) }
            ?: "mp3"

        val tempFile = File(targetDir, "tmp_${System.currentTimeMillis()}_${System.nanoTime() and 0xFFFF}.$extension")
        return runCatching {
            val copiedBytes = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            } ?: 0L

            if (copiedBytes <= 0L) {
                runCatching { tempFile.delete() }
                null
            } else {
                tempFile
            }
        }.getOrElse {
            runCatching { tempFile.delete() }
            null
        }
    }

    private fun finalizeLocalImportedFile(
        copiedFile: File,
        track: Track,
        systemFolderActive: Boolean,
    ): File {
        if (!copiedFile.exists()) return copiedFile
        val ext = copiedFile.extension.ifBlank { "mp3" }
        if (systemFolderActive) {
            return moveFileIntoSystemMusicFolder(
                sourceFile = copiedFile,
                title = track.title,
                artist = track.artist,
                fingerprint = track.trackFingerprint,
            ) ?: copiedFile
        }
        val importedDir = File(context.filesDir, "imported_audio").apply { mkdirs() }
        val stableFile = File(importedDir, "imp_${track.trackFingerprint.take(24)}.$ext")
        if (stableFile == copiedFile) return copiedFile
        if (stableFile.exists() && stableFile.length() > 0L) {
            runCatching { copiedFile.delete() }
            return stableFile
        }
        return if (copiedFile.renameTo(stableFile)) {
            stableFile
        } else {
            copiedFile
        }
    }

    /**
     * Elimina el archivo de audio si pertenece a los directorios internos administrados
     * (`filesDir/downloads`, `filesDir/opened_audio`, `filesDir/imported_audio`) o a `Aura Music/Música`
     * cuando la carpeta del sistema está activa (evitando que vuelva a importarse automáticamente al reiniciar).
     */
    suspend fun deleteOwnedDownloadFileIfExists(contentUri: String) = withContext(Dispatchers.IO) {
        val parsed = runCatching { contentUri.toUri() }.getOrNull() ?: return@withContext
        if (parsed.scheme != "file") return@withContext
        val path = parsed.path ?: return@withContext
        val target = File(path)
        val canonicalTarget = runCatching { target.canonicalPath }.getOrNull() ?: return@withContext

        val ownedDirs = buildList {
            add(File(context.filesDir, "downloads"))
            add(File(context.filesDir, "opened_audio"))
            add(File(context.filesDir, "imported_audio"))
            if (isSystemFolderActive()) {
                add(AuraSystemStorageManager.getMusicDir())
                add(AuraSystemStorageManager.getRootDir())
            }
        }
        val belongsToAppStorage = ownedDirs.any { dir ->
            val canonicalDir = runCatching { dir.canonicalPath }.getOrNull()
            canonicalDir != null && canonicalTarget.startsWith(canonicalDir + File.separator)
        }
        if (belongsToAppStorage && target.exists()) {
            runCatching { target.delete() }
        }
    }

    private fun collectAudioFilesRecursively(
        directory: DocumentFile,
        output: MutableList<Uri>,
    ) {
        val children = directory.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                collectAudioFilesRecursively(child, output)
            } else if (child.isFile && isAudioDocument(child)) {
                output.add(child.uri)
            }
        }
    }

    private fun isAudioDocument(doc: DocumentFile): Boolean {
        val mime = doc.type.orEmpty().lowercase()
        if (mime.startsWith("audio/")) return true
        val ext = doc.name.orEmpty().substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    /**
     * Convierte una imagen elegida de la galería del usuario a WebP sin pérdida de calidad
     * con máxima compresión y la guarda en `filesDir/covers/` (o `Aura Music/Carátulas/` si
     * está activa la carpeta pública del sistema) fuera del hilo principal.
     */
    suspend fun saveCustomCoverAsLosslessWebp(
        trackId: Long,
        imageUri: Uri,
    ): String? = withContext(Dispatchers.IO) {
        val coversDir = resolveCoversDirectory(isSystemFolderActive())
        val outputFile = File(coversDir, "custom_${trackId}_${System.currentTimeMillis()}.webp")

        val decoded = decodeGalleryBitmapSafely(imageUri, maxEdgePx = 1080) ?: return@withContext null
        try {
            withContext(Dispatchers.Default) {
                FileOutputStream(outputFile).use { out ->
                    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        @Suppress("DEPRECATION")
                        Bitmap.CompressFormat.WEBP
                    }
                    // quality = 100 en WEBP_LOSSLESS aplica el máximo esfuerzo de compresión sin pérdida.
                    val ok = decoded.compress(format, 100, out)
                    if (!ok) {
                        error("No se pudo comprimir la carátula a WebP")
                    }
                }
            }
            outputFile.absolutePath
        } catch (_: Exception) {
            runCatching { outputFile.delete() }
            null
        } finally {
            if (!decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }

    private fun decodeGalleryBitmapSafely(uri: Uri, maxEdgePx: Int): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val longest = max(w, h)
                    if (longest > maxEdgePx && longest > 0) {
                        val scale = maxEdgePx.toFloat() / longest.toFloat()
                        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
                        val targetH = (h * scale).roundToInt().coerceAtLeast(1)
                        decoder.setTargetSize(targetW, targetH)
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val longest = max(bounds.outWidth, bounds.outHeight)
                var sampleSize = 1
                while (longest / sampleSize > maxEdgePx * 2) {
                    sampleSize *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                } ?: return@runCatching null

                val rawLongest = max(raw.width, raw.height)
                if (rawLongest > maxEdgePx && rawLongest > 0) {
                    val scale = maxEdgePx.toFloat() / rawLongest.toFloat()
                    val targetW = (raw.width * scale).roundToInt().coerceAtLeast(1)
                    val targetH = (raw.height * scale).roundToInt().coerceAtLeast(1)
                    val scaled = raw.scale(targetW, targetH)
                    if (scaled !== raw) raw.recycle()
                    scaled
                } else {
                    raw
                }
            }
        }.getOrNull()
    }

    private suspend fun processAudioUris(
        uris: List<Uri>,
        accentArgb: Int,
        treeUriPersisted: Boolean = false,
    ) {
        val total = uris.size
        if (total == 0) {
            _progress.value = ImportProgress(
                isImporting = false,
                lastMessage = "No se encontraron archivos de audio compatibles.",
            )
            return
        }

        _progress.value = ImportProgress(
            isImporting = true,
            processedFiles = 0,
            totalFiles = total,
            importedCount = 0,
            skippedDuplicates = 0,
        )

        val systemFolderActive = isSystemFolderActive()

        // Una sola consulta por lotes a Room en lugar de consultar canción por canción.
        val (dbUris, dbFingerprints) = repository.getAllTrackIdentities()
        val seenUris = dbUris.toMutableSet()
        val seenFingerprints = dbFingerprints.toMutableSet()

        val pendingUris = ArrayList<Uri>(total)
        var skippedPrecheck = 0
        for (uri in uris) {
            if (!seenUris.add(uri.toString())) {
                skippedPrecheck++
            } else {
                pendingUris.add(uri)
            }
        }

        val processedCounter = AtomicInteger(skippedPrecheck)
        val importedCounter = AtomicInteger(0)
        val skippedCounter = AtomicInteger(skippedPrecheck)

        _progress.value = ImportProgress(
            isImporting = true,
            processedFiles = skippedPrecheck,
            totalFiles = total,
            importedCount = 0,
            skippedDuplicates = skippedPrecheck,
        )

        val tracksToInsert = mutableListOf<Track>()
        val stateMutex = Mutex()
        val workerCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val semaphore = Semaphore(workerCount)

        coroutineScope {
            pendingUris.map { uri ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        // Si el proveedor SAF no otorgó permiso persistible, copiamos el audio a
                        // filesDir/imported_audio para que el proceso :player nunca falle por SecurityException.
                        val needsLocalCopy = uri.scheme == "content" &&
                            !treeUriPersisted &&
                            !hasPersistedReadPermission(uri)
                        val copiedFile = if (needsLocalCopy) {
                            copyContentUriToManagedDir(uri, dirName = "imported_audio")
                        } else {
                            null
                        }
                        val sourceUri = if (copiedFile != null) Uri.fromFile(copiedFile) else uri

                        val parsed = extractTrackWithCover(sourceUri, accentArgb)
                        if (parsed == null) {
                            if (copiedFile != null && copiedFile.exists()) {
                                runCatching { copiedFile.delete() }
                            }
                        } else {
                            var isNewTrack = false
                            var stabilizedTrack = parsed
                            stateMutex.withLock {
                                if (seenFingerprints.add(parsed.trackFingerprint)) {
                                    isNewTrack = true
                                } else {
                                    skippedCounter.incrementAndGet()
                                }
                            }
                            if (isNewTrack) {
                                if (copiedFile != null) {
                                    val finalFile = finalizeLocalImportedFile(
                                        copiedFile = copiedFile,
                                        track = parsed,
                                        systemFolderActive = systemFolderActive,
                                    )
                                    stabilizedTrack = parsed.copy(contentUri = Uri.fromFile(finalFile).toString())
                                }
                                stateMutex.withLock {
                                    if (seenUris.add(stabilizedTrack.contentUri) || copiedFile == null) {
                                        tracksToInsert.add(stabilizedTrack)
                                        importedCounter.incrementAndGet()
                                    } else {
                                        skippedCounter.incrementAndGet()
                                    }
                                }
                            } else if (copiedFile != null && copiedFile.exists()) {
                                runCatching { copiedFile.delete() }
                            }
                        }
                        val done = processedCounter.incrementAndGet()
                        _progress.value = ImportProgress(
                            isImporting = true,
                            processedFiles = done,
                            totalFiles = total,
                            importedCount = importedCounter.get(),
                            skippedDuplicates = skippedCounter.get(),
                        )
                    }
                }
            }.awaitAll()
        }

        if (tracksToInsert.isNotEmpty()) {
            repository.insertTracks(tracksToInsert)
        }

        val imported = importedCounter.get()
        val skipped = skippedCounter.get()
        val summary = buildString {
            append("Importadas $imported ")
            append(if (imported == 1) "canción" else "canciones")
            if (skipped > 0) {
                append(" ($skipped ya estaban en tu biblioteca)")
            }
        }

        _progress.value = ImportProgress(
            isImporting = false,
            processedFiles = total,
            totalFiles = total,
            importedCount = imported,
            skippedDuplicates = skipped,
            lastMessage = summary,
        )
    }

    /**
     * Extrae metadatos, carátula y huella digital en Rust para un archivo local dentro de
     * `Aura Music/Música`, guardando su carátula directamente en `coversDirOverride`.
     */
    suspend fun extractTrackFromLocalFile(
        audioFile: File,
        coversDirOverride: File,
        accentArgb: Int,
    ): Track? = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || !audioFile.isFile || audioFile.length() <= 0L) {
            return@withContext null
        }
        extractTrackWithCover(
            uri = Uri.fromFile(audioFile),
            accentArgb = accentArgb,
            coversDirOverride = coversDirOverride,
        )
    }

    private suspend fun isSystemFolderActive(): Boolean {
        val enabled = storagePreferencesRepository?.systemFolderEnabled?.first() ?: false
        return enabled && AuraSystemStorageManager.hasStoragePermission(context)
    }

    private fun resolveCoversDirectory(systemFolderActive: Boolean): File {
        val dir = if (systemFolderActive) {
            AuraSystemStorageManager.getCoversDir()
        } else {
            File(context.filesDir, "covers")
        }
        if (!dir.exists()) {
            dir.mkdirs()
        }
        if (systemFolderActive && dir.isDirectory) {
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) {
                runCatching { noMedia.createNewFile() }
            }
        }
        return dir
    }

    private fun moveFileIntoSystemMusicFolder(
        sourceFile: File,
        title: String,
        artist: String,
        fingerprint: String,
    ): File? {
        if (!sourceFile.exists() || sourceFile.length() <= 0L) return null
        val musicDir = AuraSystemStorageManager.getMusicDir().apply { mkdirs() }
        if (!musicDir.isDirectory) return null

        val ext = sourceFile.extension.ifBlank { "mp3" }
        var dest = File(
            musicDir,
            SystemFolderHelper.buildTrackAudioFileName(
                title = title,
                artist = artist,
                extension = ext,
                fingerprint = fingerprint,
                disambiguate = false,
            ),
        )
        if (dest.exists()) {
            dest = File(
                musicDir,
                SystemFolderHelper.buildTrackAudioFileName(
                    title = title,
                    artist = artist,
                    extension = ext,
                    fingerprint = fingerprint,
                    disambiguate = true,
                ),
            )
        }
        if (dest.exists()) {
            dest = File(musicDir, "${dest.nameWithoutExtension}_${System.currentTimeMillis()}.$ext")
        }

        val tmp = File(musicDir, ".${dest.name}.tmp")
        return runCatching {
            sourceFile.inputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (dest.exists() && dest.length() > 0L) {
                runCatching { sourceFile.delete() }
                dest
            } else {
                null
            }
        }.getOrElse {
            runCatching { tmp.delete() }
            null
        }
    }

    private suspend fun extractTrackWithCover(
        uri: Uri,
        accentArgb: Int,
        coversDirOverride: File? = null,
    ): Track? {
        val localFile = if (uri.scheme == "file") uri.path?.let(::File) else null
        val docFile = if (localFile == null) DocumentFile.fromSingleUri(context, uri) else null
        val rawFallbackName = localFile?.nameWithoutExtension ?: docFile?.name?.substringBeforeLast('.')
        val fallbackName = rawFallbackName?.takeIf { it.isNotBlank() } ?: "Pista local"
        val initialSizeBytes = localFile?.length() ?: docFile?.length() ?: 0L

        return try {
            // Rust lee el audio directamente desde el descriptor POSIX sin copiar el archivo en RAM.
            var resolvedSizeBytes = initialSizeBytes
            val metadata = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (resolvedSizeBytes <= 0L) {
                    resolvedSizeBytes = pfd.statSize.coerceAtLeast(0L)
                }
                AuraRustBridge.extractAudioMetadata(pfd.fd)
            } ?: return null

            val title = metadata.title?.takeIf { it.isNotBlank() } ?: fallbackName
            val artist = metadata.artist?.takeIf { it.isNotBlank() } ?: "Artista desconocido"
            val album = metadata.album?.takeIf { it.isNotBlank() } ?: "Biblioteca local"
            val durationMs = metadata.durationMs.coerceAtLeast(0L)

            // Huella determinista calculada en Rust.
            val fingerprint = AuraRustBridge.computeTrackFingerprint(
                title = title,
                artist = artist,
                durationMs = durationMs,
                sizeBytes = resolvedSizeBytes,
            )

            val coversDir = coversDirOverride ?: resolveCoversDirectory(isSystemFolderActive())
            val embeddedBytes = metadata.embeddedCoverBytes
            val (coverPath, isProcedural) = coverWriteMutex.withLock {
                if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                    saveEmbeddedCover(fingerprint, embeddedBytes, coversDir) to false
                } else {
                    // Sin carátula embebida: Rust genera una carátula procedural única (sin IA).
                    val seedKey = "$title|$artist|$album|$fingerprint"
                    val path = AuraRustBridge.generateAndSaveProceduralCover(
                        context = context,
                        fingerprint = fingerprint,
                        seedKey = seedKey,
                        accentArgb = accentArgb,
                        outputDirectory = coversDir,
                    )
                    path to true
                }
            }

            Track(
                contentUri = uri.toString(),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                coverFilePath = coverPath,
                isProceduralCover = isProcedural,
                isLiked = false,
                trackFingerprint = fingerprint,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun saveEmbeddedCover(
        fingerprint: String,
        bytes: ByteArray,
        coversDir: File,
    ): String {
        if (!coversDir.exists()) coversDir.mkdirs()
        val extension = detectImageExtension(bytes)
        val file = File(coversDir, "emb_${fingerprint.take(24)}.$extension")
        if (!file.exists() || file.length() == 0L) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
            }
        }
        return file.absolutePath
    }

    companion object {
        /**
         * Detecta el formato binario real de una carátula embebida mediante sus magic bytes,
         * evitando guardar archivos PNG o WebP con extensión `.jpg` incorrecta.
         */
        fun detectImageExtension(bytes: ByteArray): String {
            if (bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() &&
                bytes[4] == 0x0D.toByte() &&
                bytes[5] == 0x0A.toByte() &&
                bytes[6] == 0x1A.toByte() &&
                bytes[7] == 0x0A.toByte()
            ) {
                return "png"
            }
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return "jpg"
            }
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() &&
                bytes[11] == 'P'.code.toByte()
            ) {
                return "webp"
            }
            if (bytes.size >= 6 &&
                bytes[0] == 'G'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte()
            ) {
                return "gif"
            }
            if (bytes.size >= 2 &&
                bytes[0] == 'B'.code.toByte() &&
                bytes[1] == 'M'.code.toByte()
            ) {
                return "bmp"
            }
            return "img"
        }
    }
}
