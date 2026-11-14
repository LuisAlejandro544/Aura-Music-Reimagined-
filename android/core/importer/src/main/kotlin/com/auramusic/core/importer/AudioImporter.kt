package com.auramusic.core.importer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            processAudioUris(uris, accentArgb)
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
            processAudioUris(audioUris, accentArgb)
        }
    }

    fun clearLastMessage() {
        _progress.value = _progress.value.copy(lastMessage = null)
    }

    /**
     * Elimina archivos de carátula huérfanos en `filesDir/covers` que ya no pertenezcan
     * a ninguna canción registrada en Room.
     */
    suspend fun cleanupOrphanCovers(): Int = withContext(Dispatchers.IO) {
        val coversDir = File(context.filesDir, "covers")
        if (!coversDir.exists() || !coversDir.isDirectory) return@withContext 0

        val referencedPaths = repository.getAllReferencedCoverPaths()
            .mapNotNull { path -> runCatching { File(path).canonicalPath }.getOrNull() }
            .toSet()

        var removed = 0
        coversDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val canonical = runCatching { file.canonicalPath }.getOrNull()
                if (canonical != null && canonical !in referencedPaths) {
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        removed++
                    }
                }
            }
        }
        removed
    }

    /**
     * Importa un archivo de audio descargado localmente por `:core:downloader` hacia Room.
     * Lee duración/etiquetas/carátula embebida directamente en Rust (`lofty`), convierte la
     * miniatura descargada a WebP sin pérdida si existe, o genera una carátula procedural Rust.
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

        val contentUri = Uri.fromFile(audioFile).toString()
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

        if (contentUri in dbUris || fingerprint in dbFingerprints) {
            return@withContext null
        }

        val embeddedBytes = metadata?.embeddedCoverBytes
        val (coverPath, isProcedural) = coverWriteMutex.withLock {
            if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                saveEmbeddedCover(fingerprint, embeddedBytes) to false
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
        repository.insertTracks(listOf(track))
        track
    }

    /**
     * Elimina el archivo descargado si pertenece al directorio interno `filesDir/downloads`.
     */
    suspend fun deleteOwnedDownloadFileIfExists(contentUri: String) = withContext(Dispatchers.IO) {
        val parsed = runCatching { Uri.parse(contentUri) }.getOrNull() ?: return@withContext
        if (parsed.scheme != "file") return@withContext
        val path = parsed.path ?: return@withContext
        val downloadsDir = File(context.filesDir, "downloads")
        val target = File(path)
        val canonicalDir = runCatching { downloadsDir.canonicalPath }.getOrNull() ?: return@withContext
        val canonicalTarget = runCatching { target.canonicalPath }.getOrNull() ?: return@withContext
        if (canonicalTarget.startsWith(canonicalDir + File.separator) && target.exists()) {
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
     * con máxima compresión y la guarda en `filesDir/covers/` fuera del hilo principal.
     */
    suspend fun saveCustomCoverAsLosslessWebp(
        trackId: Long,
        imageUri: Uri,
    ): String? = withContext(Dispatchers.IO) {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
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
                    val scaled = Bitmap.createScaledBitmap(raw, targetW, targetH, true)
                    if (scaled !== raw) raw.recycle()
                    scaled
                } else {
                    raw
                }
            }
        }.getOrNull()
    }

    private suspend fun processAudioUris(uris: List<Uri>, accentArgb: Int) {
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
                        val parsed = extractTrackWithCover(uri, accentArgb)
                        if (parsed != null) {
                            stateMutex.withLock {
                                if (seenFingerprints.add(parsed.trackFingerprint)) {
                                    tracksToInsert.add(parsed)
                                    importedCounter.incrementAndGet()
                                } else {
                                    skippedCounter.incrementAndGet()
                                }
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

    private suspend fun extractTrackWithCover(uri: Uri, accentArgb: Int): Track? {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val fallbackName = docFile?.name
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: "Pista local"
        val sizeBytes = docFile?.length() ?: 0L

        return try {
            // Rust lee el audio directamente desde el descriptor POSIX sin copiar el archivo en RAM.
            val metadata = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
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
                sizeBytes = sizeBytes,
            )

            val embeddedBytes = metadata.embeddedCoverBytes
            val (coverPath, isProcedural) = coverWriteMutex.withLock {
                if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
                    saveEmbeddedCover(fingerprint, embeddedBytes) to false
                } else {
                    // Sin carátula embebida: Rust genera una carátula procedural única (sin IA).
                    val seedKey = "$title|$artist|$album|$fingerprint"
                    val path = AuraRustBridge.generateAndSaveProceduralCover(
                        context = context,
                        fingerprint = fingerprint,
                        seedKey = seedKey,
                        accentArgb = accentArgb,
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

    private fun saveEmbeddedCover(fingerprint: String, bytes: ByteArray): String {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
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
