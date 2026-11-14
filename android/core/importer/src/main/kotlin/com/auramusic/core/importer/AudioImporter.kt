package com.auramusic.core.importer

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.auramusic.core.database.MusicRepository
import com.auramusic.core.model.ImportProgress
import com.auramusic.core.model.Track
import com.auramusic.core.rust.AuraRustBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Motor de importación manual de música local para Aura Music.
 *
 * Soporta tres flujos explícitos iniciados por el usuario:
 * 1. Importación individual (1 canción).
 * 2. Importación por tandas (selección múltiple de canciones).
 * 3. Importación de una carpeta completa (recorrido recursivo con DocumentFile).
 *
 * Todo el recorrido de archivos, lectura de etiquetas ID3/Vorbis y generación procedural
 * de carátulas en Rust se ejecuta fuera del hilo principal ([Dispatchers.IO] / [Dispatchers.Default]).
 */
class AudioImporter(
    private val context: Context,
    private val repository: MusicRepository,
) {
    private val _progress = MutableStateFlow(ImportProgress())
    val progress: StateFlow<ImportProgress> = _progress.asStateFlow()

    private val audioExtensions = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "alac", "aiff",
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

        val tracksToInsert = mutableListOf<Track>()
        var imported = 0
        var skipped = 0

        uris.forEachIndexed { index, uri ->
            val parsed = extractTrackWithCover(uri, accentArgb)
            if (parsed != null) {
                val alreadyExists = repository.existsByUriOrFingerprint(
                    uri = parsed.contentUri,
                    fingerprint = parsed.trackFingerprint,
                )
                if (alreadyExists) {
                    skipped++
                } else {
                    tracksToInsert.add(parsed)
                    imported++
                }
            }

            _progress.value = ImportProgress(
                isImporting = true,
                processedFiles = index + 1,
                totalFiles = total,
                importedCount = imported,
                skippedDuplicates = skipped,
            )
        }

        if (tracksToInsert.isNotEmpty()) {
            repository.insertTracks(tracksToInsert)
        }

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

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val title = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: fallbackName

            val artist = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Artista desconocido"

            val album = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "Biblioteca local"

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L

            // Huella determinista calculada en Rust.
            val fingerprint = AuraRustBridge.computeTrackFingerprint(
                title = title,
                artist = artist,
                durationMs = durationMs,
                sizeBytes = sizeBytes,
            )

            val embeddedBytes = retriever.embeddedPicture
            val (coverPath, isProcedural) = if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
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
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun saveEmbeddedCover(fingerprint: String, bytes: ByteArray): String {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(coversDir, "emb_${fingerprint.take(24)}.jpg")
        if (!file.exists() || file.length() == 0L) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
            }
        }
        return file.absolutePath
    }
}
