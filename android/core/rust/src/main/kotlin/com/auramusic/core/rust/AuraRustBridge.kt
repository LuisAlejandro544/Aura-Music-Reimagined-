package com.auramusic.core.rust

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Resultado de metadatos y carátula embebida extraídos directamente por el motor Rust (`lofty`).
 */
data class RustAudioMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val embeddedCoverBytes: ByteArray?,
)

/**
 * Puente Kotlin <-> Rust (`libaura_core.so`).
 *
 * Toda la lógica de lectura de metadatos (ID3/Vorbis/FLAC/MP4/WAV), generación procedural
 * de carátulas y cálculo criptográfico de huellas se ejecuta directamente en Rust (sin fallbacks
 * en Kotlin), despachando el trabajo pesado en [Dispatchers.Default] y la escritura de archivos
 * en [Dispatchers.IO] para no bloquear jamás el hilo principal de la interfaz.
 */
object AuraRustBridge {

    init {
        System.loadLibrary("aura_core")
    }

    private external fun nativeGetEngineInfo(): String

    private external fun nativeGenerateCoverRgba(
        seedKey: String,
        width: Int,
        height: Int,
        accentArgb: Int,
    ): ByteArray

    private external fun nativeComputeTrackFingerprint(
        title: String,
        artist: String,
        durationMs: Long,
        sizeBytes: Long,
    ): String

    private external fun nativeExtractAudioMetadata(fd: Int): ByteArray

    /**
     * Devuelve la información del motor nativo Rust compilado para la arquitectura actual.
     */
    fun getEngineInfo(): String = nativeGetEngineInfo()

    /**
     * Lee etiquetas, duración y carátula embebida directamente en Rust a partir de un
     * descriptor de archivo (`fd`) abierto por SAF. Devuelve `null` si el archivo no es válido.
     */
    suspend fun extractAudioMetadata(fd: Int): RustAudioMetadata? = withContext(Dispatchers.Default) {
        if (fd < 0) return@withContext null
        val encoded = nativeExtractAudioMetadata(fd)
        AuraMetadataCodec.decode(encoded)
    }

    /**
     * Calcula en Rust la huella digital SHA-256 de una pista en un hilo de cómputo.
     */
    suspend fun computeTrackFingerprint(
        title: String,
        artist: String,
        durationMs: Long,
        sizeBytes: Long,
    ): String = withContext(Dispatchers.Default) {
        nativeComputeTrackFingerprint(title, artist, durationMs, sizeBytes)
    }

    /**
     * Genera en Rust una carátula procedural cuadrada y la guarda como imagen PNG en caché local.
     * Devuelve la ruta absoluta del archivo generado para cargarlo de forma fluida con Coil.
     */
    suspend fun generateAndSaveProceduralCover(
        context: Context,
        fingerprint: String,
        seedKey: String,
        accentArgb: Int = 0,
        sizePx: Int = 512,
    ): String = withContext(Dispatchers.Default) {
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val outputFile = File(coversDir, "proc_${fingerprint.take(24)}.png")
        if (outputFile.exists() && outputFile.length() > 0L) {
            return@withContext outputFile.absolutePath
        }

        val rgbaBytes = nativeGenerateCoverRgba(
            seedKey = seedKey,
            width = sizePx,
            height = sizePx,
            accentArgb = accentArgb,
        )
        require(rgbaBytes.size == sizePx * sizePx * 4) {
            "Tamaño de buffer RGBA inesperado desde Rust: ${rgbaBytes.size}"
        }

        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgbaBytes))

        withContext(Dispatchers.IO) {
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            bitmap.recycle()
        }

        outputFile.absolutePath
    }
}
